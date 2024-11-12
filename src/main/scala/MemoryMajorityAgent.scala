import akka.actor.ActorRef

import scala.util.Random
import scala.math.{abs, log}
import java.util.UUID

// Memory-less-Confidence Agent

// Actor
class MemoryMajorityAgent(id: UUID, stopThreshold: Float, distribution: Distribution, networkSaver: ActorRef,
                            staticAgentDataSaver: ActorRef, networkId: UUID)
  extends DeGrootianAgent {
    // Belief related
    var publicBelief: Float = -1f
    var prevPublicBelief: Float = -1f
    
    // Belief update
    var speaking: Boolean = true
    
    // Process before receive from parent
    private def receive_before: Receive = {
        case SetInitialState(initialBelief, toleranceRadius, name) =>
            belief = initialBelief
            prevBelief = belief
            publicBelief = belief
            prevPublicBelief = belief
            tolRadius = toleranceRadius
            this.name = name
    }
    
    // Process after receive from parent
    private def receive_after: Receive = super.receive.orElse {
        case RequestBelief(roundSentFrom) =>
            sender() ! SendBelief(prevPublicBelief, self)
        
        case SaveAgentStaticData =>
            staticAgentDataSaver ! SendStaticData(StaticAgentData(
                id, 
                networkId, 
                neighbors.size, 
                tolRadius, 
                tolOffset, 
                None,
                None, 
                "majority", 
                "memory", 
                "DeGroot",
                Option(name).filter(_.nonEmpty)
            ))
        
        case UpdateAgent(forceBeliefUpdate) =>
            prevBelief = belief
            prevPublicBelief = publicBelief
            
            if (round == 0) {
                if (!hasUpdatedInfluences) generateInfluences()
                
                // Save Network structure
                networkSaver ! SendNeighbors(neighbors)
                
                // Save first round state
                snapshotAgentState(true)
            }
            round += 1
            unstashAll()
            
            fetchBeliefsFromNeighbors { beliefs =>
                var inFavor = 0
                var against = 0
//                var countOf = s"Agent: ${self.path.name}, Round $round, Belief: $prevBelief\n"
                beliefs.foreach {
                    case SendBelief(neighborBelief, neighbor) =>
                        if (isCongruent(neighborBelief)) inFavor += 1
                        else against += 1
                        belief += (neighborBelief - prevBelief) * neighbors(neighbor)
                }
                speaking = inFavor >= against
                if (speaking) publicBelief = belief
                
                // Save the round state
                // snapshotAgentState()
                network ! AgentUpdated(speaking, publicBelief, belief == prevBelief, true)
            }
        
        case SnapShotAgent =>
            snapshotAgentState(true)
    }
    
    override def receive: Receive = receive_before.orElse(super.receive).orElse(receive_after)
    
    override def preStart(): Unit = {
        distribution match {
            case Uniform =>
                belief = randomBetweenF()
                publicBelief = belief
            
            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution
            
            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
            
            case BiModal(peak1, peak2, lower, upper) =>
            
            case _ =>
            
        }
    }
    
    private def snapshotAgentState(forceSnapshot: Boolean = false): Unit = {
        if (prevBelief != belief || forceSnapshot || true) {
            val dbSaver = RoundDataRouters.getDBSaver(MemoryMajority)
            dbSaver ! MemoryMajorityRound(
                id, round, speaking, belief, publicBelief
            )
        }
    }
    
}
