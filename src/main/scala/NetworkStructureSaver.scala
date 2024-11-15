import akka.actor.{Actor, ActorRef}

import scala.collection.mutable.ArrayBuffer
import java.util.UUID

case class NetworkStructure
(
  source: UUID,
  target: UUID,
  value: Float
)

class NetworkStructureSaver(numberOfAgents: Int) extends Actor {
    var networkStructure: ArrayBuffer[NetworkStructure] = ArrayBuffer[NetworkStructure]()
    var agentsSaved = 0
    
    def receive: Receive = {
        case SendNeighbors(refs, weights, size) =>
            val senderName = sender().path.name
            val target = UUID.fromString(senderName)
            
            var i = 0
            while (i < size) {
                val source = UUID.fromString(refs(i).path.name)
                networkStructure += NetworkStructure(source, target, weights(i))
                i += 1
            }
            
            agentsSaved += 1
            if (agentsSaved == numberOfAgents) {
                DatabaseManager.insertNetworkStructureBatch(networkStructure.toArray)
                networkStructure.clear()
            }
    }
}
