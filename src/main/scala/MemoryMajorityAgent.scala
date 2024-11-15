import akka.actor.ActorRef

import java.util.UUID

// Memory-less-Confidence Agent

// Actor
class MemoryMajorityAgent(id: UUID, stopThreshold: Float, distribution: Distribution,
                          override val networkSaver: ActorRef,
                          staticAgentDataSaver: ActorRef, networkId: UUID)
  extends DeGrootianAgent {
    // Belief related
    var publicBelief: Float = -1f
    
    // Belief update
    var speaking: Boolean = true
    
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
                None,
                None,
                "majority",
                "memory",
                "DeGroot",
                Option(name).filter(_.nonEmpty)
            ))
        
        case SendBelief(neighborBelief) =>
            if (isCongruent(neighborBelief)) inFavor += 1
            else against += 1
            beliefChange += (neighborBelief - belief) * getInfluence(sender())
            updateStateAndNotify()
        
        case UpdateAgent | UpdateAgentForce =>
            updateRound()
            var i = 0
            while (i < neighborsSize) {
                neighborsRefs(i) ! SendBelief(publicBelief)
                i += 1
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
    
    private def updateStateAndNotify(): Unit = {
        neighborsReceived += 1
        if (neighborsReceived == neighborsSize) {
            neighborsReceived = 0
            belief += beliefChange
            speaking = inFavor >= against
            if (speaking) publicBelief = belief
            if (beliefChange < stopThreshold) timesStable += 1
            else timesStable = 0
            // snapshotAgentState(true)
            network ! AgentUpdated(speaking, publicBelief, beliefChange < stopThreshold && timesStable > 1, true)
            beliefChange = 0f
            inFavor = 0
            against = 0
        }
    }
    
    protected def snapshotAgentState(forceSnapshot: Boolean = false): Unit = {
        if (beliefChange != 0 || forceSnapshot) {
            val dbSaver = RoundDataRouters.getDBSaver(MemoryMajority)
            dbSaver ! MemoryMajorityRound(
                id, round, speaking, belief, publicBelief
            )
        }
    }
    
}
