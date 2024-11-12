import akka.actor.ActorRef

import scala.util.Random
import scala.math.log

import java.util.UUID

// Memory-less-Confidence Agent

// Actor
class MemLessMajorityAgent(id: UUID, stopThreshold: Float, distribution: Distribution, networkSaver: ActorRef,
                             staticAgentDataSaver: ActorRef, networkId: UUID)
  extends DeGrootianAgent {
    // Belief update
    var speaking: Boolean = true
    var prevSpeaking: Boolean = true
    var timesStable: Int = 0
    
    override def receive: Receive = super.receive.orElse {
        case RequestBelief(roundSentFrom) if prevSpeaking =>
            sender() ! SendBelief(prevBelief, self)
        
        case RequestBelief(roundSentFrom) =>
            sender() ! SendBelief(-1f, self)
        
        case SaveAgentStaticData =>
            staticAgentDataSaver ! SendStaticData(StaticAgentData(
                id, 
                networkId, 
                neighbors.size, 
                tolRadius, 
                tolOffset, 
                None,
                None, 
                "confidence", 
                "memory-less", 
                "DeGroot",
                Option(name).filter(_.nonEmpty)
            ))
        
        case UpdateAgent(forceBeliefUpdate) =>
            prevBelief = belief
            prevSpeaking = speaking
            if (round == 0) {
                if (!hasUpdatedInfluences) generateInfluences()
                
                // Save Network structure
                networkSaver ! SendNeighbors(neighbors)
                //println(neighbors.mkString(s"${self.path.name} Array(", ", ", ")"))
                
                // Save first round state
                snapshotAgentState(forceSnapshot = true)
            }
            round += 1
            unstashAll()
            
            fetchBeliefsFromNeighbors { beliefs =>
                var inFavor = 0
                var against = 0
                var selfInfluenceSummed = selfInfluence
                beliefs.foreach {
                    case SendBelief(neighborBelief, neighbor) if neighborBelief == -1f =>
                        selfInfluenceSummed += neighbors(neighbor)
                    
                    case SendBelief(neighborBelief, neighbor) =>
                        if (isCongruent(neighborBelief)) inFavor += 1
                        else against += 1
                        belief += (neighborBelief - prevBelief) * neighbors(neighbor)
                }
                
                speaking = inFavor >= against
                // println(s"$name, Round:$round, Prev:$prevBelief, Belief:$belief, Speaking:$speaking")
                // Save the round state
                // snapshotAgentState(selfInfluenceSummed)
                if (belief == prevBelief) {
                    timesStable += 1
                } else {
                    timesStable = 0
                }
                
                network ! AgentUpdated(speaking, belief, belief == prevBelief & timesStable > 1, true)
            }
        
        case SnapShotAgent =>
            snapshotAgentState(selfInfluence, forceSnapshot = true)    
    }
    
    override def preStart(): Unit = {
        distribution match {
            case Uniform =>
                belief = randomBetweenF()
            
            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution
            
            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
            
            case BiModal(peak1, peak2, lower, upper) =>
            
            case _ =>
            
        }
    }
    
    private def updateBeliefs(): Unit = {
        
    }
    
    private def snapshotAgentState(selfInfluence: Float = selfInfluence, forceSnapshot: Boolean = false): Unit = {
        if (prevBelief != belief || forceSnapshot || prevSpeaking != speaking) {
            val dbSaver = RoundDataRouters.getDBSaver(MemoryLessMajority)
            dbSaver ! MemoryLessMajorityRound(
                id, round, speaking, selfInfluence, belief
            )
        }
    }
    
}
