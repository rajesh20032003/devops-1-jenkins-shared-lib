def call(String stageName, Closure body) {
  def start = System.currentTimeMillis()
  try {
    body()
  } finally {
    // Always push duration even if the stage fails
    def durationMs = System.currentTimeMillis() - start
    recordMetrics(
      stage:      stageName.replaceAll('[^a-zA-Z0-9]', '_'),
      durationMs: durationMs
    )
  }
}