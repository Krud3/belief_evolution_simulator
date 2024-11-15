import akka.actor.ActorRef

import scala.util.Random

import java.util.UUID

// Memory-less-Confidence Agent

// Actor
class MemoryConfidenceAgent(id: UUID, stopThreshold: Float, distribution: Distribution, 
                            override val networkSaver: ActorRef,
                            staticAgentDataSaver: ActorRef, networkId: UUID)
  extends DeGrootianAgent {
    // Belief related
    var publicBelief: Float = -1f
    
    // Confidence related
    var beliefExpressionThreshold: Float = -1f
    var perceivedOpinionClimate: Float = 0.0f
    var confidenceUnbounded: Float = -1f
    var confidence: Float = -1f
    
    // Belief update
    val openMindedness: Int = 50 // randomIntBetween(1, 100)
    var curInteractions: Int = 0
    var forceBeliefUpdate: Boolean = true
    
    // Process before receive from parent
    private def receive_before: Receive = {
        case SetInitialState(initialBelief, toleranceRadius, name) =>
            belief = initialBelief
            publicBelief = belief
            tolRadius = toleranceRadius
            this.name = name
    }
    
    // Process after receive from parent
    private def receive_after: Receive = super.receive.orElse {
        case SaveAgentStaticData =>
            staticAgentDataSaver ! SendStaticData(StaticAgentData(
                id, 
                networkId,
                neighborsSize, 
                tolRadius, 
                tolOffset, 
                Some(beliefExpressionThreshold),
                Some(openMindedness), 
                "confidence", 
                "memory", 
                "DeGroot",
                Option(name).filter(_.nonEmpty)
            ))
        
        case SendBelief(neighborBelief) =>
            if (isCongruent(neighborBelief)) inFavor += 1
            else against += 1
            beliefChange += (neighborBelief - belief) * getInfluence(sender())
            updateStateAndNotify()
        
        case UpdateAgent =>
            updateRound()
            this.forceBeliefUpdate = false
            var i = 0
            while (i < neighborsSize) {
                neighborsRefs(i) ! SendBelief(publicBelief)
                i += 1
            }
        
        case UpdateAgentForce =>
            updateRound()
            this.forceBeliefUpdate = true
            var i = 0
            while (i < neighborsSize) {
                neighborsRefs(i) ! SendBelief(publicBelief)
                i += 1
            }
            
    }
    
    override def receive: Receive = receive_before.orElse(super.receive).orElse(receive_after)
    
    private def updateStateAndNotify(): Unit = {
        neighborsReceived += 1
        if (neighborsReceived == neighborsSize) {
            neighborsReceived = 0
            
            curInteractions += 1
            if (curInteractions == openMindedness || forceBeliefUpdate) {
                curInteractions = 0
                belief += beliefChange
            }
            
            if (confidence >= beliefExpressionThreshold) publicBelief = belief
            perceivedOpinionClimate = inFavor + against match {
                case 0 => 0.0f
                case totalSpeaking => (inFavor - against).toFloat / totalSpeaking
            }
            confidenceUnbounded = math.max(confidenceUnbounded + perceivedOpinionClimate, 0)
            val newConfidence = (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f
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
    
    override def snapshotAgentState(forceSnapshot: Boolean = false): Unit = {
        var localBelief: Option[Float] = Some(belief)
        var localPublicBelief: Option[Float] = Some(publicBelief)
//        if (belief == prevBelief || !forceSnapshot) localBelief = None
//        if (publicBelief == prevPublicBelief || !forceSnapshot) localPublicBelief = None
//        if (localBelief.isEmpty & (confidence == prevConfidence)) return
        if (beliefChange != 0 || forceSnapshot) {
            val dbSaver = RoundDataRouters.getDBSaver(MemoryConfidence)
            dbSaver ! MemoryConfidenceRound(
                id, round, confidence >= beliefExpressionThreshold, confidence, perceivedOpinionClimate,
                localBelief, localPublicBelief
            )
        }
    }
    
}
