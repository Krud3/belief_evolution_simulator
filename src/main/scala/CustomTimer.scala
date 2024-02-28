import scala.concurrent.duration._

class CustomTimer {
    private var startTime: Option[Long] = None
    private var endTime: Option[Long] = None

    def start(): Unit = {
        startTime = Some(System.nanoTime())
    }

    def stop(message: String, timeUnit: TimeUnit = MILLISECONDS): Unit = {
        endTime = Some(System.nanoTime())
        val duration = endTime.get - startTime.getOrElse(throw new IllegalStateException("Timer was not started"))

        val durationInUnit = timeUnit match {
            case NANOSECONDS => duration
            case MICROSECONDS => duration / 1000
            case MILLISECONDS => duration / 1000000
            case SECONDS => duration / 1000000000
            case MINUTES => duration / 60000000000L
            case HOURS => duration / 3600000000000L
            case DAYS => duration / 86400000000000L
            case _ => throw new UnsupportedOperationException("Unsupported time unit")
        }

        println(s"$message: $durationInUnit ${timeUnit.toString.toLowerCase}")
    }
}