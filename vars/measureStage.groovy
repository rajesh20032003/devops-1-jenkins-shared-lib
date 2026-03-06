def call(String stageName, Closure body) {
  def start = System.currentTimeMillis()
  try {
    body()
  } finally {
    def durationMs = System.currentTimeMillis() - start
    echo "Stage [${stageName}] completed in ${durationMs}ms"
    recordMetrics(
      stage:      stageName.replaceAll('[^a-zA-Z0-9]', '_'),
      durationMs: durationMs
    )
  }
}