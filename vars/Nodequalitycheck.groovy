/**
 * nodeQualityCheck(service, extraSteps, lintOnly)
 *
 * Runs quality checks for a Node.js service inside a Kubernetes pod.
 *
 * For backend services (gateway, user-service, order-service):
 *   - lint + unit tests + coverage
 *   - publishes junit results and coverage to Jenkins UI
 *   - stashes coverage for SonarQube stage
 *
 * For frontend (lintOnly = true):
 *   - only runs npm run lint:html
 *   - no tests, no coverage, no stash
 *   - frontend has no unit tests currently
 *
 * How it works behind the scenes:
 *   1. podTemplate() registers pod definition with Kubernetes plugin
 *   2. node(POD_LABEL) triggers pod creation in jenkins namespace on Harbor VM
 *   3. jnlp container connects back to Jenkins on port 50000
 *   4. checkout scm clones repo into shared pod workspace
 *   5. container('node') switches execution into node:22-alpine
 *   6. npm steps run inside node container
 *   7. after container('node') block — back in jnlp container
 *   8. junit, recordCoverage, stash run in jnlp (debian, has all tools)
 *   9. recordMetrics pushes to pushgateway via curl (jnlp has curl)
 *  10. pod deleted automatically when node(POD_LABEL) block exits
 *
 * @param service     - service folder name e.g. 'gateway', 'frontend'
 * @param extraSteps  - optional closure for extra steps e.g. jest --clearCache
 * @param lintOnly    - true for frontend (no tests), false for backend services
 */
def call(String service, Closure extraSteps = null, Boolean lintOnly = false) {

  def metricLabel = "Quality_Check_${service.replaceAll('[^a-zA-Z0-9]', '_')}"

  // Pod YAML definition
  // Two containers:
  //   jnlp: connects back to Jenkins — MUST NOT have command/args override
  //   node: runs npm steps — needs sleep 99d to stay alive
  def podYaml = """
apiVersion: v1
kind: Pod
spec:
  containers:

  # jnlp — mandatory, handles Jenkins ↔ pod communication
  # jenkins/inbound-agent is debian-based → has curl → recordMetrics works
  - name: jnlp
    image: jenkins/inbound-agent:latest
    resources:
      requests:
        memory: "256Mi"
        cpu: "100m"
      limits:
        memory: "512Mi"
        cpu: "200m"

  # node — runs all npm commands
  # sleep 99d = keep container alive so Jenkins can send commands
  # without sleep: container exits immediately after starting → pod fails
  - name: node
    image: node:22-alpine
    command: ['sleep']
    args: ['99d']
    resources:
      requests:
        memory: "512Mi"
        cpu: "250m"
      limits:
        memory: "1Gi"
        cpu: "500m"
    volumeMounts:
    # npm cache volume — reused within the pod lifetime
    # speeds up npm ci by avoiding re-downloading packages
    - name: npm-cache
      mountPath: /home/node/.npm

  volumes:
  # emptyDir = temporary volume tied to pod lifecycle
  # created when pod starts, destroyed when pod ends
  # all containers in pod share this volume
  - name: npm-cache
    emptyDir: {}
"""

  // podTemplate() — registers the pod spec with Kubernetes plugin
  // does NOT create pod yet — just defines what it will look like
  // POD_LABEL — auto-generated unique label injected by Kubernetes plugin
  // e.g. "kubernetes-abc123" — used by node() to target this specific pod
  podTemplate(yaml: podYaml) {

    // node(POD_LABEL) — NOW the pod is actually created in K8s
    // Jenkins waits for jnlp to connect back on port 50000
    // everything inside this block runs on the pod
    // when block exits → Jenkins deletes the pod automatically
    node(POD_LABEL) {

      // clone repo into pod workspace: /home/jenkins/agent/workspace/
      // all containers share this same directory
      // node container writes files here → jnlp container can read them
      checkout scm

      def start = System.currentTimeMillis()

      // container('node') — switches execution into node:22-alpine
      // all sh steps inside run in the node container
      // files written here visible to jnlp (shared workspace volume)
      container('node') {
        dir(service) {
          // clean install — no leftover node_modules from previous builds
          sh 'rm -rf node_modules'

          // install exact versions from package-lock.json
          // --prefer-offline: use npm cache volume first
          // --no-audit: skip vuln scan (trivy handles that separately)
          // --cache: point to mounted npm cache volume
          sh 'npm ci --prefer-offline --no-audit --cache /home/node/.npm'

          if (lintOnly) {
            // ── Frontend path ────────────────────────────────────────────
            // frontend uses different lint command (HTML linter)
            // || true: lint warnings don't fail the pipeline
            // no tests, no coverage — frontend has no unit tests yet
            sh 'npm run lint:html || true'

          } else {
            // ── Backend path (gateway, user-service, order-service) ──────
            // standard lint with auto-fix
            sh 'npm run lint -- --fix'

            // run extra steps if provided
            // e.g. order-service passes: sh 'npx jest --clearCache'
            if (extraSteps) { extraSteps() }

            // run tests with full coverage reporting
            // --coverage:               generate coverage files
            // --coverageReporters=lcov: generate lcov.info for SonarQube
            // --ci:                     non-interactive, fail on error
            // --reporters=jest-junit:   generate junit XML for Jenkins UI
            sh 'npm test -- --coverage --coverageReporters=lcov --ci --reporters=default --reporters=jest-junit'
          }
        }
      }
      // ── execution back in jnlp container now ────────────────────────────
      // coverage files written by node container are visible here
      // because both containers mount the same workspace volume

      if (!lintOnly) {
        // publish test results to Jenkins UI
        // reads junit XML → shows pass/fail counts in build summary
        junit allowEmptyResults: true, testResults: "${service}/coverage/junit.xml"

        // publish coverage percentages to Jenkins UI
        // reads lcov.info → shows line/branch/function coverage
        recordCoverage tools: [[parser: 'LCOV', pattern: "${service}/coverage/lcov.info"]]

        // stash coverage files for SonarQube stage
        // SonarQube runs on Jenkins VM (agent any) — different machine
        // stash copies files: pod → JNLP tunnel → Jenkins controller storage
        // SonarQube stage will unstash them before running sonar-scanner
        // without stash: coverage files die with the pod ❌
        // with stash: files survive in Jenkins until unstashed ✅
        stash name: "coverage-${service}",
              includes: "${service}/coverage/**",
              allowEmpty: true
      }

      // recordMetrics runs in jnlp container (debian-based, has curl)
      // pushes duration metric to Pushgateway → visible in Grafana
      // previously failed when running inside node:22-alpine (no curl)
      // now works because we're back in jnlp container here
      def durationMs = System.currentTimeMillis() - start
      recordMetrics(stage: metricLabel, durationMs: durationMs)

    } // pod deleted here automatically
  }
}