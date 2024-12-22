import akka.actor.Actor

import java.util.UUID

case class StaticAgentData
(
  networkId: UUID,
  static: SendStaticData
)

class AgentStaticDataSaver(numberOfAgents: Int, networkId: UUID) extends Actor {
    private var staticAgentData: Array[StaticAgentData] = Array.ofDim[StaticAgentData](numberOfAgents)
    private var agentsSaved: Int = 0
    
    def receive: Receive = {
        case staticAgentData: SendStaticData =>
            this.staticAgentData(agentsSaved) = StaticAgentData(
                networkId,
                staticAgentData
            )
            agentsSaved += 1
            if (agentsSaved == numberOfAgents) {
                DatabaseManager.insertAgentsBatch(this.staticAgentData)
                this.staticAgentData = Array.ofDim[StaticAgentData](0)
                context.stop(self)
            }
        
    }
}
