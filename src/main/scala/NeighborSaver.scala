import akka.actor.{Actor, ActorRef}

import scala.collection.mutable.ArrayBuffer
import java.util.UUID

// Messages
case class SendNeighbors(refs: Array[ActorRef], weights: Array[Float], 
                         neighborBiases: Array[CognitiveBiasType], size: Int) // Agent -> NetworkSaver

// Actor

case class NetworkStructure
(
  source: UUID,
  target: UUID,
  value: Float,
  bias: CognitiveBiasType
)

class NeighborSaver(numberOfAgents: Int) extends Actor {
    var networkStructure: ArrayBuffer[NetworkStructure] = new ArrayBuffer[NetworkStructure](numberOfAgents)
    var agentsSaved = 0
    
    def receive: Receive = {
        case SendNeighbors(refs, weights, biases, size) =>
            val senderName = sender().path.name
            val target = UUID.fromString(senderName)
            
            var i = 0
            while (i < size) {
                val source = UUID.fromString(refs(i).path.name)
                networkStructure.append(NetworkStructure(source, target, weights(i), biases(i)))
                i += 1
            }
            
            agentsSaved += 1
            if (agentsSaved == numberOfAgents) {
                DatabaseManager.insertNeighborsBatch(networkStructure)
                context.stop(self)
            }
    }
}
