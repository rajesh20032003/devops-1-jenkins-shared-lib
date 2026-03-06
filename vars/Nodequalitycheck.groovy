def call(String service, Closure extraSteps = null) {

  dir(service) {
    sh 'set -x'
    sh 'rm -rf node_modules'
    sh 'npm ci --prefer-offline --no-audit --cache /home/node/.npm'
    sh 'npm run lint -- --fix'

    if (extraSteps) {
      extraSteps()
    }

    sh 'npm test -- --coverage --coverageReporters=lcov --ci --reporters=default --reporters=jest-junit'
  }

  // Publish results on the agent after the dir block
  junit allowEmptyResults: true, testResults: "${service}/coverage/junit.xml"
  recordCoverage tools: [[parser: 'LCOV', pattern: "${service}/coverage/lcov.info"]]
  stash name: "coverage-${service}", includes: "${service}/coverage/**", allowEmpty: true
}