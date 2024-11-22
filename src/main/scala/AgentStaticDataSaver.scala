import akka.actor.Actor

import java.util.UUID

case class StaticAgentData
(
  id: UUID,
  networkId: UUID,
  numberOfNeighbors: Int,
  toleranceRadius: Float,
  tolOffset: Float,
  beliefExpressionThreshold: Option[Float],
  openMindedness: Option[Int],
  causeOfSilence: String,
  effectOfSilence: String,
  beliefUpdateMethod: String,
  name: Option[String] = None
)

class AgentStaticDataSaver(numberOfAgents: Int) extends Actor {
    var staticAgentData: Array[StaticAgentData] = Array.ofDim[StaticAgentData](numberOfAgents)
    var agentsSaved: Int = 0
    
    def receive: Receive = {
        case SendStaticData(staticAgentData) =>
            this.staticAgentData(agentsSaved) = staticAgentData
            agentsSaved += 1
            if (agentsSaved == numberOfAgents) {
                //DatabaseManager.insertAgentsBatch(this.staticAgentData)
                DatabaseManager.insertAgentsBatch(Array(this.staticAgentData(0)))
                this.staticAgentData = Array.ofDim[StaticAgentData](0)
                context.parent ! RunFirstRound
                context.stop(self)
            }
        
    }
}
