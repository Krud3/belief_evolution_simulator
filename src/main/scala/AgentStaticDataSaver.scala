import akka.actor.Actor

import java.util.UUID
import scala.collection.IndexedSeqView

case class StaticData
(
  networkId: UUID,
  static: IndexedSeqView[StaticAgentData]
)

case class SendStaticAgentData(staticAgentData: IndexedSeqView[StaticAgentData])

class AgentStaticDataSaver(numberOfAgents: Int, networkId: UUID) extends Actor {
    private var agentsSaved: Int = 0
    
    def receive: Receive = {
        case SendStaticAgentData(staticAgentData) =>
            val staticData = StaticData(
                networkId,
                staticAgentData
            )
            agentsSaved += staticAgentData.length
            DatabaseManager.insertAgentsBatch(staticData)
            if (agentsSaved == numberOfAgents) {
                context.stop(self)
            }
        
    }
}
