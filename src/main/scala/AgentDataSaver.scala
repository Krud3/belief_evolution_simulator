import akka.actor.{Actor, ActorRef, Props}

import java.io.File

case class AgentData
(
  round: Int,
  agentName: String,
  belief: Double,
  confidence: Double,
  opinionClimate: Double,
  isSpeaking: Boolean
)

case class AgentStaticData
(
  agentName: String,
  numberOfNeighbors: Int,
  tolRadius: Double,
  tolOffset: Double,
  beliefExpressionThreshold: Double,
  openMindedness: Int
)

case class SendAgentData(round: Int, agentName: String, belief: Double, confidence: Double, opinionClimate: Double,
                         isSpeaking: Boolean)
case class SendStaticAgentData(agentName: String, numberOfNeighbors: Int, tol_radius: Double, tol_offset: Double,
                               beliefExpressionThreshold: Double, openMindedness: Int)
case object ExportCSV

case object SavedCSV

case class ExportNetwork(networkName: String)

class AgentDataSaver(dataSavingPath: String, dataSaver: ActorRef, networkSaver: ActorRef, numberOfAgents: Int,
                     network: ActorRef) extends
  Actor {
  var data = List.empty[AgentData]
  var staticData = List.empty[AgentStaticData]
  val saveThreshold = 100000 // Every ~5.5 MB save

  val networkName: String = network.path.name
  val path = s"$dataSavingPath/${networkName}.csv"
  val staticPath = s"$dataSavingPath/static_${networkName}.csv"

  def exportData(): Unit = {
    saveToCsv(
      path,
      "round,agentName,belief,confidence,opinionClimate,isSpeaking",
      data,
      d => s"${d.round},${d.agentName},${d.belief},${d.confidence},${d.opinionClimate},${d.isSpeaking}"
    )
    data = List.empty[AgentData]
  }

  def receive: Receive = {
        // about 55 bytes per save line
    case SendAgentData(round, agentName, belief, confidence, opinionClimate, isSpeaking) =>
      data = AgentData(round, agentName, belief, confidence, opinionClimate, isSpeaking) :: data
      if (data.length >= saveThreshold) {
        exportData()
      }


    case SendStaticAgentData(agentName, numberOfNeighbors, tol_radius, tol_offset, beliefExpressionThreshold,
    openMindedness) =>
      staticData = AgentStaticData(
        agentName, numberOfNeighbors, tol_radius, tol_offset, beliefExpressionThreshold, openMindedness
      ) :: staticData


    case ExportCSV =>
      exportData()

      saveToCsv(
        staticPath,
        "agentName,numberOfNeighbors,tolRadius,tolOffset,beliefExpressionThreshold,openMindedness",
        staticData,
        d => s"${d.agentName},${d.numberOfNeighbors},${d.tolRadius},${d.tolOffset},${d.beliefExpressionThreshold}," +
          s"${d.openMindedness}"
      )

      dataSaver ! SavedCSV
      networkSaver ! ExportNetwork(networkName)

  }
}
