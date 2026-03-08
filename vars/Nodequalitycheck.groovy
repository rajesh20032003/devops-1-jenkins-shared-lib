/**
 * nodeQualityCheck(service, extraSteps)
 *
 * Runs lint + unit tests + coverage for a Node.js service
 * inside a Kubernetes pod (node:22-alpine container)
 *
 * What happens behind the scenes:
 * 1. podTemplate() tells Jenkins Kubernetes plugin: "create a pod with these containers"
 * 2. node(POD_LABEL) tells Jenkins: "run the following steps on that pod"
 * 3. checkout scm: clones the repo into the pod's workspace
 * 4. container('node'): switches execution into node:22-alpine container
 * 5. npm steps run inside node container
 * 6. recordMetrics runs in jnlp container (has curl, can reach pushgateway)
 * 7. stash copies coverage files through JNLP tunnel to Jenkins controller
 * 8. pod is deleted automatically after node(POD_LABEL) block exits
 *
 * @param service     - service folder name e.g. 'gateway', 'user-service'
 * @param extraSteps  - optional closure for extra steps e.g. jest cache clear
 */
def call(String service, Closure extraSteps = null) {

  def metricLabel = "Quality_Check_${service.replaceAll('[^a-zA-Z0-9]', '_')}"

  // ── Pod definition ────────────────────────────────────────────────────────
  // This YAML defines what containers the pod will have
  // Kubernetes plugin reads this and creates the pod when job runs
  def podYaml = """
apiVersion: v1
kind: Pod
spec:
  containers:

  # jnlp container — MANDATORY
  # jenkins/inbound-agent automatically connects back to Jenkins on port 50000
  # DO NOT add command/args here — it would override the connect script
  # This container also runs: stash, unstash, recordMetrics, junit, recordCoverage
  # because those are Jenkins pipeline steps that run in the "default" container
  - name: jnlp
    image: jenkins/inbound-agent:latest
    resources:
      requests:
        memory: "256Mi"
        cpu: "100m"
      limits:
        memory: "512Mi"
        cpu: "200m"

  # node container — runs npm steps
  # sleep 99d keeps it alive so Jenkins can send commands to it
  # without sleep it would exit immediately (no default long-running cmd)
  - name: node
    image: node:22-alpine
    command: ['sleep']
    args: ['99d']
    resources:
      requests:
        memory: "512Mi"   # minimum guaranteed RAM for this container
        cpu: "250m"       # 250 millicores = 0.25 CPU cores guaranteed
      limits:
        memory: "1Gi"     # maximum RAM — K8s kills container if exceeded
        cpu: "500m"       # maximum CPU — throttled if exceeded
    volumeMounts:
    # mount npm cache volume so npm ci uses cached packages
    # without this every build downloads all packages from internet
    - name: npm-cache
      mountPath: /home/node/.npm

  volumes:
  # emptyDir = temporary volume, created when pod starts, deleted when pod ends
  # lives as long as the pod — shared between all containers in the pod
  # faster than downloading from internet, slower than persistent cache
  - name: npm-cache
    emptyDir: {}
"""

  // ── podTemplate() ─────────────────────────────────────────────────────────
  // Registers the pod definition with the Kubernetes plugin
  // Does NOT create the pod yet — just defines what it will look like
  // POD_LABEL is auto-generated unique label e.g. "quality-pod-abc123"
  def podLabel = "quality-${service}-${env.BUILD_NUMBER}"
  podTemplate(yaml: podYaml, label: "quality-${service}") {

    // ── node(POD_LABEL) ───────────────────────────────────────────────────
    // NOW the pod is actually created in K8s jenkins namespace
    // Jenkins waits for jnlp container to connect back on port 50000
    // Once connected — executes everything inside this block on the pod
    // When block exits — Jenkins deletes the pod automatically
    node(podLabel) {

      // Clone the repo into the pod's workspace
      // Without this the pod has no source code to work with
      // POD workspace = /home/jenkins/agent/workspace/
      // All containers in the pod share this same workspace directory
      checkout scm

      def start = System.currentTimeMillis()

      // ── container('node') ───────────────────────────────────────────────
      // Switches execution context INTO the node:22-alpine container
      // All sh steps inside here run in node container
      // Files written here are visible to jnlp container (shared workspace)
      container('node') {
        dir(service) {
          // Remove old node_modules — ensures clean install
          sh 'rm -rf node_modules'

          // Install dependencies using lock file
          // --prefer-offline: use cache first, download only if needed
          // --no-audit: skip security audit (we use trivy for that)
          // --cache: use mounted npm cache volume
          sh 'npm ci --prefer-offline --no-audit --cache /home/node/.npm'

          // Run linter with auto-fix
          // || true: don't fail pipeline on lint warnings
          sh 'npm run lint -- --fix'

          // Run extra steps if provided e.g. jest --clearCache
          if (extraSteps) { extraSteps() }

          // Run tests with coverage
          // --coverage: generate coverage reports
          // --coverageReporters=lcov: generate lcov.info for SonarQube
          // --ci: non-interactive mode, fail on test failure
          // --reporters=jest-junit: generate junit XML for Jenkins
          sh 'npm test -- --coverage --coverageReporters=lcov --ci --reporters=default --reporters=jest-junit'
        }
      }
      // ── back in jnlp container now ───────────────────────────────────────
      // coverage files written by node container are visible here
      // because both containers share /home/jenkins/agent/workspace/

      // Publish JUnit test results to Jenkins UI
      // reads the XML file generated by jest-junit reporter
      // shows pass/fail count in Jenkins build summary
      junit allowEmptyResults: true, testResults: "${service}/coverage/junit.xml"

      // Publish coverage report to Jenkins UI
      // reads lcov.info generated by jest --coverage
      // shows line/branch/function coverage percentages
      recordCoverage tools: [[parser: 'LCOV', pattern: "${service}/coverage/lcov.info"]]

      // Stash coverage files so SonarQube stage can use them
      // stash copies files through JNLP tunnel → Jenkins controller storage
      // SonarQube runs on Jenkins VM (agent any) — needs these files
      // without stash: coverage files die with the pod
      // with stash: files survive in Jenkins controller until unstashed
      stash name: "coverage-${service}",
            includes: "${service}/coverage/**",
            allowEmpty: true

      // Record metrics — runs in jnlp container
      // jnlp container has curl (jenkins/inbound-agent is debian-based)
      // can reach pushgateway at http://pushgateway:9091
      // previously this failed because node:22-alpine has no curl
      def durationMs = System.currentTimeMillis() - start
      recordMetrics(stage: metricLabel, durationMs: durationMs)

    } // pod deleted here — K8s removes it from jenkins namespace
  }
}