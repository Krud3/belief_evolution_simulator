import akka.actor.{Actor, ActorRef, Props}
import scala.sys.process.*

class PerRoundRunner(dataSavingPath: String) extends Actor {
  def receive: Receive = {
    case RunPerRoundAnalysis(networkName) =>
      val pathToRScript = "src/scripts/per_round_exploration.R"
      val command = s"Rscript $pathToRScript ${dataSavingPath} $networkName"

      try {
        println(s"Started running per round R Script for $networkName")
        val output = command.!!
        println(output)
        println(s"Finished running per round R Script for $networkName")

      } catch {
        case e: Exception => e.printStackTrace()
      }
  }
}
