import akka.actor.ActorRef

import scala.util.Random

import java.util.UUID

// Memory-less-Confidence Agent

// Actor
class MemLessConfidenceAgent(id: UUID, stopThreshold: Float, distribution: Distribution, 
                             override val networkSaver: ActorRef,
                             staticAgentDataSaver: ActorRef, networkId: UUID)
  extends DeGrootianAgent {
    
    // Confidence related
    var beliefExpressionThreshold: Float = -1f
    var perceivedOpinionClimate: Float = 0.0f
    var confidenceUnbounded: Float = -1f
    var confidence: Float = -1f
    
    // Belief update
    val openMindedness: Int = 1 // randomIntBetween(1, 100)
    var curInteractions: Int = 0
    var forceBeliefUpdate: Boolean = true
    
    // update related
    var snapshotAll = false
    
    override def receive: Receive = super.receive.orElse {
        case SaveAgentStaticData =>
            staticAgentDataSaver ! SendStaticData(StaticAgentData(
                id, networkId, neighborsSize, tolRadius, tolOffset, Some(beliefExpressionThreshold),
                Some(openMindedness), "confidence", "memory-less", "DeGroot", Option(name).filter(_.nonEmpty)
            ))
        
        case Silent =>
            updateStateAndNotify()
        
        case SendBelief(neighborBelief) =>
            if (isCongruent(neighborBelief)) inFavor += 1
            else against += 1
            beliefChange += (neighborBelief - belief) * getInfluence(sender())
            updateStateAndNotify()
            
        case UpdateAgent =>
            updateRound()
            this.forceBeliefUpdate = false
            val msg = if (confidence >= beliefExpressionThreshold) SendBelief(belief) else Silent
            var i = 0
            while (i < neighborsSize) {
                neighborsRefs(i) ! msg
                i += 1
            }
        
        case UpdateAgentForce =>
            updateRound()
            this.forceBeliefUpdate = true
            val msg = if (confidence >= beliefExpressionThreshold) SendBelief(belief) else Silent
            var i = 0
            while (i < neighborsSize) {
                neighborsRefs(i) ! msg
                i += 1
            }
    }
    
    private def updateStateAndNotify(): Unit = {
        neighborsReceived += 1
        if (neighborsReceived == neighborsSize) {
            neighborsReceived = 0
            
            curInteractions += 1
            if (curInteractions == openMindedness || forceBeliefUpdate) {
                curInteractions = 0
                belief += beliefChange
            }
            
            perceivedOpinionClimate = inFavor + against match {
                case 0 => 0.0f
                case totalSpeaking => (inFavor - against).toFloat / totalSpeaking
            }
            confidenceUnbounded = math.max(confidenceUnbounded + perceivedOpinionClimate, 0)
            val newConfidence =  (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f
            val aboveThreshold = math.abs(newConfidence - confidence) >= 
              stopThreshold || round == 1
            confidence = newConfidence
            
           
            if (beliefChange < stopThreshold) timesStable += 1
            else timesStable = 0
           
            // snapshotAgentState(true)
            network ! AgentUpdated(aboveThreshold, belief, beliefChange < stopThreshold & timesStable > 1,
                curInteractions == openMindedness || forceBeliefUpdate)
            beliefChange = 0f
            inFavor = 0
            against = 0
        }
    }
    
    override def preStart(): Unit = {
        distribution match {
            case Uniform =>
                belief = randomBetweenF()
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
                beliefExpressionThreshold = rands(Random.nextInt(rands.length))
                confidence = beliefExpressionThreshold
                confidenceUnbounded = reverseConfidence(confidence)
                snapshotAll = true
                
            case _ =>
            
        }
    }
    
    protected def snapshotAgentState(forceSnapshot: Boolean = false): Unit = {
        var localBelief: Option[Float] = Some(belief)
        // if (belief == prevBelief || !forceSnapshot) return //localBelief = None
        //if (localBelief.isEmpty & (confidence == prevConfidence)) return
        if (beliefChange != 0 || forceSnapshot || snapshotAll) {
            val dbSaver = RoundDataRouters.getDBSaver(MemoryLessConfidence)
            dbSaver ! MemoryLessConfidenceRound(
                id, round, confidence >= beliefExpressionThreshold, confidence, perceivedOpinionClimate,
                selfInfluence, localBelief
            )
        }
    }
    
}
