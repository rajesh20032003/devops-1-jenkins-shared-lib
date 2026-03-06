def call(String stageName, Closure body) {

    def start = System.currentTimeMillis()

    body()

    def end = System.currentTimeMillis()

    def duration = (end - start) / 1000

    echo "METRIC: ${stageName} took ${duration} seconds"

}