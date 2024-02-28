import NetworkJsonProtocol.{jsonFormat2, jsonFormat3, jsonFormat5, jsonFormat6, jsonFormat9}
import akka.actor.{Actor, ActorRef, Props}

import java.nio.file.{Files, Paths}
import spray.json.*

// Data storage classes

case class NetworkData
(
    InitialReport: InitialReportData,
    RoundReport: Vector[RoundReportData],
    FinalReport: FinalReportData
)

case class InitialReportData
(
    AgentCharacteristics: Vector[AgentCharacteristicsItem],
    density: Int,
    degreeDistributionParameter: Double,
    stopThreshHold: Double,
    distribution: Distribution
)

case class RoundReportData
(
    Round: Int,
    totalConfidenceInFavourSpeaking: Double = 0.0,
    totalConfidenceAgainstSpeaking: Double = 0.0,
    totalConfidenceInFavourSilent: Double = 0.0,
    totalConfidenceAgainstSilent: Double = 0.0,
    inFavourSpeaking: Int,
    againstSpeaking: Int,
    inFavourSilent: Int,
    againstSilent: Int
)

case class FinalReportData
(
    totalSteps: Int,
    AgentCharacteristics: Vector[AgentCharacteristicsItem]
)

case class AgentCharacteristicsItem
(
    size: Int,
    belief: Double,
    willingness: Double,
    confidence: Double,
    speaking: Boolean,
    climate: Double
)

// Json
object NetworkJsonProtocol extends DefaultJsonProtocol {

    // Custom format for Distribution
    implicit object DistributionFormat extends RootJsonFormat[Distribution] {
        override def write(obj: Distribution): JsValue = obj match {
            case Uniform => JsObject("type" -> JsString("Uniform"))
            case Normal(mean, std) => JsObject(
                "type" -> JsString("Normal"),
                "mean" -> JsNumber(mean),
                "std" -> JsNumber(std)
            )
            case Exponential(lambda) => JsObject(
                "type" -> JsString("Exponential"),
                "lambda" -> JsNumber(lambda)
            )
        }

        override def read(json: JsValue): Distribution = json.asJsObject.getFields("type") match {
            case Seq(JsString("Uniform")) => Uniform
            case Seq(JsString("Normal")) =>
                val fields = json.asJsObject.fields
                Normal(fields("mean").convertTo[Double], fields("std").convertTo[Double])
            case Seq(JsString("Exponential")) =>
                val fields = json.asJsObject.fields
                Exponential(fields("lambda").convertTo[Double])
            case _ => deserializationError("Unknown distribution type")
        }
    }

    // Other formats using the automatic generation methods provided by DefaultJsonProtocol
    implicit val agentCharacteristicsItemFormat: RootJsonFormat[AgentCharacteristicsItem] = jsonFormat6(AgentCharacteristicsItem.apply)
    implicit val initialReportDataFormat: RootJsonFormat[InitialReportData] = jsonFormat5(InitialReportData.apply)
    implicit val roundReportDataFormat: RootJsonFormat[RoundReportData] = jsonFormat9(RoundReportData.apply)
    implicit val finalReportDataFormat: RootJsonFormat[FinalReportData] = jsonFormat2(FinalReportData.apply)
    implicit val networkDataFormat: RootJsonFormat[NetworkData] = jsonFormat3(NetworkData.apply)
}


def saveDataToJson(data: Map[String, NetworkData], filePath: String): Unit = {
    import NetworkJsonProtocol._

    // Convert the data to JSON
    val jsonData = data.toJson.prettyPrint

    // Write JSON data to file
    Files.write(Paths.get(filePath), jsonData.getBytes)
}

// Saver

// Messages
case object RunMultiRoundAnalysis

// Actor
class DataSaver(initialCount: Int, dataSavingPath: String) extends Actor {
    var counter: Int = initialCount
    var csvCounter: Int = initialCount
    val threshold: Int = (initialCount * 0.05).toInt
    val multiRoundRunner: ActorRef = context.actorOf(Props(new MultiRoundRunner(dataSavingPath)))

    override def receive: Receive = {
        case SendNetworksData(data) =>
            counter -= 1
            if (counter % threshold == 0 && counter != initialCount) {
                val percentage = ((initialCount - counter) * 100) / initialCount
                println(s"${(initialCount - counter)} out of $initialCount($percentage%) processed.")
            }
            if (counter == 0) {
                val filePath = s"src/data/${initialCount}_runs_1k_agents.json"
                
                //saveDataToJson(data, filePath)
                //println(s"Data saved to $filePath")
            }
        case SavedCSV =>
            csvCounter -= 1
//            println(initialCount)
//            if (counter % threshold == 0 && csvCounter != initialCount) {
//                val percentage = ((initialCount - csvCounter) * 100) / initialCount
//                println(s"${(initialCount - csvCounter)} out of $initialCount($percentage%) processed.")
//            }
            if (csvCounter == 0) multiRoundRunner ! RunMultiRoundAnalysis
            
    }
}
