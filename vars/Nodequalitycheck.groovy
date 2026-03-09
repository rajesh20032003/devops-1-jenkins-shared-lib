def call(String service, Closure extraSteps = null, Boolean lintOnly = false) {

  def metricLabel = "Quality_Check_${service.replaceAll('[^a-zA-Z0-9]', '_')}"

  def podYaml = """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:latest
    resources:
      requests:
        memory: "256Mi"
        cpu: "100m"
      limits:
        memory: "512Mi"
        cpu: "200m"

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
    - name: npm-cache
      mountPath: /home/node/.npm

  volumes:
  - name: npm-cache
    hostPath:
      path: /tmp/npm-cache
      type: DirectoryOrCreate
"""

  podTemplate(yaml: podYaml) {
    node(POD_LABEL) {

      checkout([
        $class: 'GitSCM',
        branches: scm.branches,
        extensions: [
          [$class: 'CloneOption', shallow: true, depth: 1]
        ],
        userRemoteConfigs: scm.userRemoteConfigs
      ])

      def start = System.currentTimeMillis()

      container('node') {
        dir(service) {
          sh 'rm -rf node_modules'
          sh 'npm ci --prefer-offline --no-audit --cache /home/node/.npm'

          if (lintOnly) {
            sh 'npm run lint:html || true'
          } else {
            sh 'npm run lint -- --fix'
            if (extraSteps) { extraSteps() }
            sh 'npm test -- --coverage --coverageReporters=lcov --ci --reporters=default --reporters=jest-junit'
          }
        }
      }

      if (!lintOnly) {
        junit allowEmptyResults: true, testResults: "${service}/coverage/junit.xml"
        recordCoverage tools: [[parser: 'LCOV', pattern: "${service}/coverage/lcov.info"]]
        stash name: "coverage-${service}",
              includes: "${service}/coverage/**",
              allowEmpty: true
        archiveArtifacts artifacts: "${service}/coverage/lcov-report/**",
                 allowEmptyArchive: true
      }

      def durationMs = System.currentTimeMillis() - start
      recordMetrics(stage: metricLabel, durationMs: durationMs)

    }
  }
}