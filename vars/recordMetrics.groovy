def call(Map args = [:]) {
  withCredentials([
    string(credentialsId: 'pushgateway-url', variable: 'PUSHGATEWAY_URL')
  ]) {
    script {
      def jobName     = (env.JOB_NAME ?: 'unknown').replaceAll('[^a-zA-Z0-9_]', '_')
      def buildNumber = env.BUILD_NUMBER ?: '0'
      def stageName   = (args.stage ?: 'unknown').replaceAll('[^a-zA-Z0-9_]', '_')
      def durationMs  = args.durationMs != null ? (args.durationMs as Long) : null
      def result      = args.result

      def lines = []

      // ── Stage / pipeline duration ──────────────────────────────────────
      if (durationMs != null) {
        def durationSec = durationMs / 1000.0
        lines << "# HELP ci_stage_duration_seconds Wall-clock time for a CI stage in seconds"
        lines << "# TYPE ci_stage_duration_seconds gauge"
        lines << "ci_stage_duration_seconds{job=\"${jobName}\",stage=\"${stageName}\",build=\"${buildNumber}\"} ${durationSec}"
      }

      // ── Build result (pipeline post block only) ────────────────────────
      if (result != null) {
        def successVal = (result == 'success') ? 1 : 0
        def failureVal = (result == 'failure') ? 1 : 0

        lines << "# HELP ci_build_success 1 if the build succeeded, 0 otherwise"
        lines << "# TYPE ci_build_success gauge"
        lines << "ci_build_success{job=\"${jobName}\",build=\"${buildNumber}\"} ${successVal}"

        lines << "# HELP ci_build_failure 1 if the build failed, 0 otherwise"
        lines << "# TYPE ci_build_failure gauge"
        lines << "ci_build_failure{job=\"${jobName}\",build=\"${buildNumber}\"} ${failureVal}"
      }

      if (!lines) {
        echo "recordMetrics: nothing to push — provide durationMs or result"
        return
      }

      def payload = lines.join('\n') + '\n'

      // Pushgateway grouping key: job label + build number
      // Using PUT so each build gets its own slot and old data is replaced
      writeFile file: 'metrics_payload.txt', text: payload
      sh """
        curl --silent --show-error --fail \
          --request PUT \
          --data-binary @metrics_payload.txt \
          "\${PUSHGATEWAY_URL}/metrics/job/${jobName}/instance/build_${buildNumber}" \
          && echo "Pushed metrics: stage=${stageName} build=${buildNumber}" \
          || echo "WARNING: Failed to push metrics (non-blocking)"
      """
    }
  }
}