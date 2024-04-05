import akka.actor.{Actor, ActorRef, Props}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.io.File

// Data creation path
def createRunDirectory(basePath: String): String = {
    val currentDateTime = LocalDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS")
    val formattedDateTime = currentDateTime.format(formatter)

    val dirName = s"run_$formattedDateTime"
    val fullPath = s"$basePath/$dirName"

    // Create the directory
    val directory = new File(fullPath)
    if (!directory.exists()) {
        val result = directory.mkdirs()
        if (result) {
            println(s"Directory created successfully at $fullPath")
        } else {
            println(s"Failed to create directory at $fullPath")
        }
    } else {
        println(s"Directory already exists at $fullPath")
    }
    fullPath
}

// Monitor

// Modes of operation
sealed trait OperationMode

case object Debug extends OperationMode
case object Run extends OperationMode

val mode: String = "Run"

// Messages
case class CreateNetwork  // Monitor -> Network
(
  numberOfAgents: Int,
  density: Int,
  stopThreshold: Double,
  degreeDistributionParameter: Double,
  distribution: Distribution
)
case object BuildNetwork // Monitor -> network
case object RunNetwork // Monitor -> network
case object StartAnalysis // Monitor -> network
case object RunBatch // Monitor -> self

// Actor
class Monitor(operationMode: OperationMode, numOfNetworks: Int) extends Actor {
    // Networks
    val networks: Array[ActorRef] = Array.ofDim[ActorRef](numOfNetworks + 1)
    var networkNumber: Int = 0

    // Timing
    val totalTimer = new CustomTimer()
    val batchTimer = new CustomTimer()
    val totalBatchTimer = new CustomTimer()
    val buildingTimer: Array[CustomTimer] = Array.ofDim[CustomTimer](numOfNetworks + 1)
    val runningTimer: Array[CustomTimer] = Array.ofDim[CustomTimer](numOfNetworks + 1)
    val analysingTimer = new CustomTimer()

    // Data saving
    val dataSavingPath: String =  createRunDirectory("src/data/runs")
    val dataSaver: ActorRef = context.actorOf(Props(new DataSaver(numOfNetworks, dataSavingPath)), name = "DataSaver")

    // Batches
    var curBatch: Int = 0
    var numberOfBatches: Int = 0
    var batchSize: Int = 0
    var numberOfNetworksFinished: Int = 0
    var networkInfo: (Int, Int, Double, Double, Distribution) = (0, 0, 0.0, 0.0, Uniform)

    def receive: Receive = {
        case CreateNetworkBatch(batchSize, batchNumber, numberOfAgents, density, stopThreshold,
        degreeDistributionParameter, distribution) =>
            totalTimer.start()
            this.batchSize = batchSize
            this.numberOfBatches = batchNumber
            networkInfo = (numberOfAgents, density, stopThreshold, degreeDistributionParameter, distribution)
            self ! RunBatch
            totalBatchTimer.start()

        case RunBatch =>
            batchTimer.start()
            for (i <- 0 until batchSize) {
                self ! CreateNetwork(
                    networkInfo._1, networkInfo._2,
                    networkInfo._3, networkInfo._4,
                    networkInfo._5)
            }
            curBatch += 1


        case CreateNetwork(numberOfAgents, density, stopThreshold, degreeDistributionParameter, distribution) =>
            networkNumber += 1
            // Create a new Network actor
            val newNetwork = context.actorOf(Props(new Network(numberOfAgents, density, degreeDistributionParameter,
                stopThreshold, distribution, self, dataSavingPath, dataSaver)),
                s"Network${networkNumber}_density$density"
            )

            // Add the new Network actor reference to the networks sequence
            networks(networkNumber) = newNetwork

            // Build the network
            buildingTimer(networkNumber) = new CustomTimer()
            buildingTimer(networkNumber).start()
            newNetwork ! BuildNetwork


        case BuildingComplete =>
            val network = sender()
            val networkName = network.path.name
            val networkNumber = "\\d+".r.findFirstIn(networkName).map(_.toInt).getOrElse(0)
            buildingTimer(networkNumber).stop(s"$networkName building took")
            runningTimer(networkNumber) = new CustomTimer()
            runningTimer(networkNumber).start()
            network ! RunNetwork


        case RunningComplete =>
            //caseClassToString(reportData)
            val network = sender()
            val networkName = network.path.name
            val networkNumber = "\\d+".r.findFirstIn(networkName).map(_.toInt).getOrElse(0)
            runningTimer(networkNumber).stop(s"$networkName was running for")
            operationMode match {
                case Debug =>
                    network ! StartAnalysis
                    analysingTimer.start()
                case Run =>
                    numberOfNetworksFinished += 1

                    if (curBatch >= numberOfBatches & numberOfNetworksFinished >= batchSize) {
                        batchTimer.stop(s"Batch$curBatch was running for")
                        totalBatchTimer.stop(s"Batches were running for")
                        for (i <- 1 to numOfNetworks) {
                            networks(i) ! StartAnalysis
                        }
                        analysingTimer.start()
                    } else if (numberOfNetworksFinished >= batchSize) {
                        numberOfNetworksFinished = 0
                        self ! RunBatch
                        batchTimer.stop(s"Batch$curBatch was running for")
                    }
            }
            
        case AnalysisComplete =>
            analysingTimer.stop(s"Analysis running for")
            
            totalTimer.stop("Total time elapsed")

    }
}
