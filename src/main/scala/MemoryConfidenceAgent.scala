import akka.actor.ActorRef

import scala.util.Random
import scala.math.log

import java.util.UUID

// Memory-less-Confidence Agent

// Actor
class MemoryConfidenceAgent(id: UUID, stopThreshold: Float, distribution: Distribution, networkSaver: ActorRef,
                            staticAgentDataSaver: ActorRef, networkId: UUID)
  extends DeGrootianAgent {
    // Belief related
    var publicBelief: Float = -1f
    var prevPublicBelief: Float = -1f
    
    // Confidence related
    var beliefExpressionThreshold: Float = -1f
    var perceivedOpinionClimate: Float = 0.0f
    var confidenceUnbounded: Float = -1f
    var confidence: Float = -1f
    var prevConfidence: Float = -1f
    
    // Belief update
    val openMindedness: Int = 50 // randomIntBetween(1, 100)
    var curInteractions: Int = 0
    
    // Process before receive from parent
    private def receive_before: Receive = {
        case SetInitialState(initialBelief, toleranceRadius, name) =>
            belief = initialBelief
            prevBelief = belief
            publicBelief = belief
            tolRadius = toleranceRadius
            this.name = name
    }
    
    // Process after receive from parent
    private def receive_after: Receive = super.receive.orElse {
        case RequestBelief(roundSentFrom) =>
            sender() ! SendBelief(publicBelief, self)
        
        case SaveAgentStaticData =>
            staticAgentDataSaver ! SendStaticData(StaticAgentData(
                id, 
                networkId, 
                neighbors.size, 
                tolRadius, 
                tolOffset, 
                Some(beliefExpressionThreshold),
                Some(openMindedness), 
                "confidence", 
                "memory", 
                "DeGroot",
                Option(name).filter(_.nonEmpty)
            ))
        
        case UpdateAgent(forceBeliefUpdate) =>
            prevBelief = belief
            prevPublicBelief = publicBelief
            prevConfidence = confidence
            
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

                beliefs.foreach {
                    case SendBelief(neighborBelief, neighbor) =>
                        if (isCongruent(neighborBelief)) inFavor += 1
                        else against += 1
                        belief += (neighborBelief - prevBelief) * neighbors(neighbor)
                }
                
                curInteractions += 1
                if (curInteractions == openMindedness || forceBeliefUpdate) 
                    curInteractions = 0
                else 
                    belief = prevBelief
                    if (confidence >= beliefExpressionThreshold) publicBelief = belief
                
                
                perceivedOpinionClimate = inFavor + against match {
                    case 0 => 0.0f
                    case totalSpeaking => (inFavor - against).toFloat / totalSpeaking
                }
                confidenceUnbounded = math.max(confidenceUnbounded + perceivedOpinionClimate, 0)
                confidence = (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f
                
                val aboveThreshold = math.abs(confidence - prevConfidence) >= stopThreshold || round == 1
                // Save the round state
                // snapshotAgentState()
                network ! AgentUpdated(aboveThreshold, belief, belief == prevBelief,
                    curInteractions == openMindedness || forceBeliefUpdate)
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
                
                beliefExpressionThreshold = Random.nextFloat()
                confidence = beliefExpressionThreshold
                confidenceUnbounded = reverseConfidence(confidence)
            
            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution
            
            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
            
            case BiModal(peak1, peak2, lower, upper) =>
            
            case CustomDistribution =>
                val rands = Array(
                    0.4f,
                    0.4f,
                    0.4f
                )
                beliefExpressionThreshold = Random.nextFloat()
                confidence = beliefExpressionThreshold
                confidenceUnbounded = reverseConfidence(confidence)
                
            case _ =>
            
        }
    }
    
    private def snapshotAgentState(forceSnapshot: Boolean = false): Unit = {
        var localBelief: Option[Float] = Some(belief)
        var localPublicBelief: Option[Float] = Some(publicBelief)
//        if (belief == prevBelief || !forceSnapshot) localBelief = None
//        if (publicBelief == prevPublicBelief || !forceSnapshot) localPublicBelief = None
//        if (localBelief.isEmpty & (confidence == prevConfidence)) return
        if (belief != prevBelief || forceSnapshot) {
            val dbSaver = RoundDataRouters.getDBSaver(MemoryConfidence)
            dbSaver ! MemoryConfidenceRound(
                id, round, confidence >= beliefExpressionThreshold, confidence, perceivedOpinionClimate,
                localBelief, localPublicBelief
            )
        }
    }
    
}
