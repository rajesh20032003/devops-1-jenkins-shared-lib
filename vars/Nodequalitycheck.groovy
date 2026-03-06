def call(String service, Closure extraSteps = null) {
  def metricLabel = "Quality_Check_${service.replaceAll('[^a-zA-Z0-9]', '_')}"
  def start = System.currentTimeMillis()

  dir(service) {
    sh 'rm -rf node_modules'
    sh 'npm ci --prefer-offline --no-audit --cache /home/node/.npm'
    sh 'npm run lint -- --fix'
    if (extraSteps) { extraSteps() }
    sh 'npm test -- --coverage --coverageReporters=lcov --ci --reporters=default --reporters=jest-junit'
  }

  junit allowEmptyResults: true, testResults: "${service}/coverage/junit.xml"
  recordCoverage tools: [[parser: 'LCOV', pattern: "${service}/coverage/lcov.info"]]
  stash name: "coverage-${service}", includes: "${service}/coverage/**", allowEmpty: true

  // runs AFTER docker container closes — on the host agent where curl exists
  def durationMs = System.currentTimeMillis() - start
  recordMetrics(stage: metricLabel, durationMs: durationMs)
}