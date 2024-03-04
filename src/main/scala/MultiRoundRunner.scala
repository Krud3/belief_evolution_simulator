import akka.actor.{Actor, ActorRef, Props}
import scala.io.Source
import java.nio.file.Paths
import java.io.File
import scala.sys.process.*

case class RunPerRoundAnalysis(networkName: String)
case object AnalysisComplete

class MultiRoundRunner(dataSavingPath: String) extends Actor {
  var scriptsCompleted = 0
  var totalNumberOfScripts = 0

  def processSimulations(simulationNames: List[String]): Unit = {
    simulationNames.foreach { networkName =>
      val perRoundRunner: ActorRef = context.actorOf(Props(new PerRoundRunner(dataSavingPath)),
        name = s"PerRoundRunnerOf$networkName")
      perRoundRunner ! RunPerRoundAnalysis(networkName)
    }
  }

  def readSimulationNamesFromCsv(filePath: String): List[String] = {
    val source = Source.fromFile(filePath)
    val lines = source.getLines().toList
    val simulationNames = lines.tail.map { line => // Skip header
      line.split(",").head.trim // Assuming SimulationName is the first column
    }
    source.close()
    simulationNames
  }

  def receive: Receive = {
    case RunMultiRoundAnalysis =>
      val pathToRScript = "src/scripts/multi_round_analysis.R"
      val command = s"Rscript $pathToRScript $dataSavingPath"

      try {
        println("Started running multi round R Script")
        val output = command.!!
        println(output)
        println("Finished running per round R Script")
        val rOutputPath = Paths.get(dataSavingPath, "r_outputs").toString
        val averageSimulationPath = Paths.get(rOutputPath, "average_simulation.csv").toString
        val outliersPaths = Paths.get(rOutputPath, "outliers.csv").toString

        val averageSimulations = readSimulationNamesFromCsv(averageSimulationPath)
        val outliers = readSimulationNamesFromCsv(outliersPaths)
        totalNumberOfScripts = averageSimulations.length + outliers.length

        processSimulations(averageSimulations)
        processSimulations(outliers)

      } catch {
        case e: Exception => e.printStackTrace()
      }
    case PerRoundAnalysisComplete =>
      scriptsCompleted += 1
      if (totalNumberOfScripts == scriptsCompleted) {
        val monitor = context.system.actorSelection("akka://original/user/Monitor")
        monitor ! AnalysisComplete
      }
  }

}
