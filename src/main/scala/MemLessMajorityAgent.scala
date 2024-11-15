import akka.actor.ActorRef

import java.util.UUID

// Memory-less-Confidence Agent

// Actor
class MemLessMajorityAgent(id: UUID, stopThreshold: Float, distribution: Distribution,
                           override val networkSaver: ActorRef,
                           staticAgentDataSaver: ActorRef, networkId: UUID)
  extends DeGrootianAgent {
    // Belief update
    var speaking: Boolean = true
    
    override def receive: Receive = super.receive.orElse {
        case SaveAgentStaticData =>
            staticAgentDataSaver ! SendStaticData(StaticAgentData(
                id, 
                networkId,
                neighborsSize,
                tolRadius, 
                tolOffset, 
                None,
                None, 
                "majority",
                "memory-less", 
                "DeGroot",
                Option(name).filter(_.nonEmpty)
            ))
        
        case Silent =>
            updateStateAndNotify()
            
        case SendBelief(neighborBelief) =>
            if (isCongruent(neighborBelief)) inFavor += 1
            else against += 1
            beliefChange += (neighborBelief - belief) * getInfluence(sender())
            updateStateAndNotify()
        
        case UpdateAgent | UpdateAgentForce =>
            updateRound()
            val msg = if (speaking) SendBelief(belief) else Silent
            var i = 0
            while (i < neighborsSize) {
                neighborsRefs(i) ! msg
                i += 1
            }
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
    
    private def updateStateAndNotify(): Unit = {
        neighborsReceived += 1
        if (neighborsReceived == neighborsSize) {
            neighborsReceived = 0
            belief += beliefChange
            speaking = inFavor >= against
            if (beliefChange < stopThreshold) timesStable += 1
            else timesStable = 0
            // snapshotAgentState(true)
            network ! AgentUpdated(speaking, belief, beliefChange < stopThreshold && timesStable > 1, true)
            beliefChange = 0f
            inFavor = 0
            against = 0
        }
    }
    
    override def snapshotAgentState(forceSnapshot: Boolean = false): Unit = {
        if (beliefChange != 0 || forceSnapshot) {
            val dbSaver = RoundDataRouters.getDBSaver(MemoryLessMajority)
            dbSaver ! MemoryLessMajorityRound(
                id, round, speaking, selfInfluence, belief
            )
        }
    }
    
}
