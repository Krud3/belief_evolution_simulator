package io.persistence.actors

import akka.actor.Actor
import core.simulation.actors.{ActorFinished, StaticAgentData}
import io.db.DatabaseManager

import java.util.UUID
import scala.collection.IndexedSeqView

case class StaticData
(
  networkId: UUID,
  static: Array[StaticAgentData]
)

case class SendStaticAgentData(staticAgentData: Array[StaticAgentData])

class AgentStaticDataSaver(numberOfAgents: Int, networkId: UUID) extends Actor {
    private var agentsSaved: Int = 0
    
    def receive: Receive = {
        case SendStaticAgentData(staticAgentData) =>
            val staticData = StaticData(
                networkId,
                staticAgentData
            )
            agentsSaved += 1
            DatabaseManager.insertAgentsBatch(staticData)
            if (agentsSaved == numberOfAgents) {
                context.parent ! ActorFinished
                context.stop(self)
            }
        
    }
}
