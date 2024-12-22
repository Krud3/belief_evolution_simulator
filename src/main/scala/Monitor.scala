import akka.actor.{Actor, ActorRef, Props}

import scala.collection.mutable.ArrayBuffer

// Monitor

// Containers

case class RunMetadata(
    runMode: RunMode,
    saveMode: SaveMode,
    distribution: Distribution,
    startTime: Long,
    optionalMetaData: Option[OptionalMetadata],
    var runId: Option[Int],
    var agentLimit: Int,
    numberOfNetworks: Int,
    agentsPerNetwork: Int,
    iterationLimit: Int,
    stopThreshold: Float
)

case class OptionalMetadata(
    recencyFunction: Option[(Float, Int) => Float],
    density: Option[Int],
    degreeDistribution: Option[Float]
)

// Messages
case class AddNetworks(
    agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
    agentBiases: Array[(CognitiveBiasType, Float)],
    distribution: Distribution,
    saveMode: SaveMode,
    recencyFunction: Option[(Float, Int) => Float],
    numberOfNetworks: Int,
    density: Int,
    iterationLimit: Int,
    degreeDistribution: Float,
    stopThreshold: Float
)

case class AddSpecificNetwork(agents: Array[AgentInitialState],
    neighbors: Array[Neighbors],
    distribution: Distribution,
    saveMode: SaveMode,
    stopThreshold: Float,
    iterationLimit: Int,
    name: String,
    recencyFunction: Option[(Float, Int) => Float]
)

case class AgentInitialState(
    name: String,
    initialBelief: Float,
    toleranceRadius: Float,
    toleranceOffset: Float,
    silenceStrategy: SilenceStrategyType,
    silenceEffect: SilenceEffectType
)

case class Neighbors(
    source: String,
    target: String,
    influence: Float,
    bias: CognitiveBiasType
)

case object RunComplete // Monitor -> Run

// Actor
class Monitor extends Actor {
    // Limits
    val agentLimit: Int = 100_000
    var currentUsage: Int = agentLimit
    
    // Router
    val saveThreshold: Int = 2_000_000
    RoundRouter.setSavers(context, saveThreshold)
    
    // Runs
    var activeRuns: ArrayBuffer[ActorRef] = ArrayBuffer.empty[ActorRef]
    var totalRuns: Int = 0
    
    // Testing performance end
    
    def receive: Receive = {
        case AddSpecificNetwork(agents, neighbors, distribution, saveMode, stopThreshold, iterationLimit, 
                                name, recencyFunction) =>
            totalRuns += 1
            val optionalMetadata = {
                if (recencyFunction.isEmpty) None
                else Some(OptionalMetadata(recencyFunction, None, None))
            }
            
            val runMetadata = RunMetadata(
                RunMode.Custom,
                saveMode,
                distribution,
                System.currentTimeMillis(),
                optionalMetadata,
                None,
                1000000,
                1, agents.length, iterationLimit, stopThreshold)
            activeRuns += context.actorOf(Props(new Run(runMetadata, agents, neighbors, name)), s"$totalRuns")
        
        case AddNetworks(agentTypeCount, agentBiases, distribution, saveMode, recencyFunction, numberOfNetworks,
                         density, iterationLimit, degreeDistribution, stopThreshold) =>
            val optionalMetadata = Some(OptionalMetadata(recencyFunction, Some(density), Some(degreeDistribution)))
            val runMetadata = RunMetadata(
                RunMode.Generated,
                saveMode,
                distribution,
                System.currentTimeMillis(),
                optionalMetadata,
                None,
                1000000,
                numberOfNetworks, agentTypeCount.map(_._3).sum, iterationLimit, stopThreshold)
            totalRuns += 1
            
            activeRuns += context.actorOf(Props(new Run(runMetadata, agentTypeCount, agentBiases)), s"$totalRuns")
            
            activeRuns.last ! StartRun
        
        case RunComplete =>
            println("\nThe run has been complete\n")
    }
    
    def reBalanceUsage(): Unit = {
        
    }
    
    
}
