import DatabaseManager.RunQueryResult
import akka.actor.{Actor, ActorRef, Props}

import java.util.UUID
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

case class AddSpecificNetwork(
    agents: Array[AgentInitialState],
    neighbors: Array[Neighbors],
    distribution: Distribution,
    saveMode: SaveMode,
    stopThreshold: Float,
    iterationLimit: Int,
    name: String,
    recencyFunction: Option[(Float, Int) => Float]
)

case class AddNetworksFromExistingRun(
    runId: Int,
    agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
    agentBiases: Array[(CognitiveBiasType, Float)],
    recencyFunction: Option[(Float, Int) => Float],
    saveMode: SaveMode,
    stopThreshold: Float,
    iterationLimit: Int
)

case class AddNetworksFromExistingNetwork(
    networkId: UUID,
    agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
    agentBiases: Array[(CognitiveBiasType, Float)],
    recencyFunction: Option[(Float, Int) => Float],
    saveMode: SaveMode,
    stopThreshold: Float,
    iterationLimit: Int
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
    val agentLimit: Int = 16_777_216 // 10_485_760 // 4_194_304 1_048_576 8_388_608 2_097_152
    var currentUsage: Int = agentLimit
    
    // Router
    val saveThreshold: Int = 2_000_000
    RoundRouter.setSavers(context, saveThreshold)
    
    // Runs
    var activeRuns: ArrayBuffer[ActorRef] = ArrayBuffer.empty[ActorRef]
    var totalRuns: Int = 0
    
    // Experimental
    var densityRunner: Option[ActorRef] = None
    
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
                agentLimit,
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
                agentLimit,
                numberOfNetworks, agentTypeCount.map(_._3).sum, iterationLimit, stopThreshold)
            totalRuns += 1
            
            activeRuns += context.actorOf(Props(new Run(runMetadata, agentTypeCount, agentBiases)), s"R$totalRuns")
            activeRuns.last ! StartRun
        
        case AddNetworksFromExistingRun(runId, agentTypeCount, agentBiases, recencyFunction, saveMode,
                      stopThreshold, iterationLimit) =>
            val baseRun = DatabaseManager.getRun(runId)
            baseRun match {
                case Some(RunQueryResult(numberOfNetworks, iterationLimit, stopThreshold, distribution,
                                         density, degreeDistribution)) =>
                    val optionalMetadata = Some(OptionalMetadata(recencyFunction, density, degreeDistribution))
                    val runMetadata = RunMetadata(
                        RunMode.Generated,
                        saveMode,
                        Distribution.fromString(distribution).get,
                        System.currentTimeMillis(),
                        optionalMetadata,
                        None,
                        agentLimit,
                        numberOfNetworks,
                        agentTypeCount.map(_._3).sum,
                        iterationLimit,
                        stopThreshold)
                    
                    activeRuns += context.actorOf(Props(new Run(runMetadata, agentTypeCount, agentBiases,
                                                                runId)), s"R$totalRuns")
                    activeRuns.last ! StartRun
                case None => println("Error: Run not found")
            }
        
        case AddNetworksFromExistingNetwork(networkId, agentTypeCount, agentBiases, recencyFunction, saveMode,
                          stopThreshold, iterationLimit) =>
            val baseRun = DatabaseManager.getRunInfo(networkId)
            baseRun match
                case Some((distribution, density, degreeDistribution)) =>
                    val optionalMetadata = Some(OptionalMetadata(recencyFunction, density, degreeDistribution))
                    val runMetadata = RunMetadata(
                        RunMode.Generated,
                        saveMode,
                        Distribution.fromString(distribution).get,
                        System.currentTimeMillis(),
                        optionalMetadata,
                        None,
                        agentLimit,
                        1,
                        agentTypeCount.map(_._3).sum,
                        iterationLimit,
                        stopThreshold)
                    activeRuns += context.actorOf(Props(new Run(runMetadata, agentTypeCount, agentBiases,
                                                                networkId)), s"R$totalRuns")
                    activeRuns.last ! StartRun
                case _ => println("Error: Network not found")
            
            
        case RunComplete =>
            println("\nThe run has been complete\n")
            densityRunner match {
                case Some(dRunner: ActorRef) =>
                    dRunner ! RunNextDensityRun
                case _ =>
            }
        
        case GetDensityRunner(dRunner) =>
            densityRunner = Some(dRunner)
            densityRunner.get ! RunNextDensityRun
            
    }
    
    def reBalanceUsage(): Unit = {
        
    }
    
    
}

case class GetDensityRunner(densityRunner: ActorRef)
case object RunNextDensityRun
class DensityRunner(startDensity: Int = 1, stopDensity: Int = 12, monitor: ActorRef, numberOfAgents: Int, 
    numberOfNetworks: Int) extends Actor {
    private var curDensity: Int = startDensity
    private var count: Int = 0
    
    def receive: Receive = {
        case RunNextDensityRun =>
            curDensity += 1
            if (curDensity <= stopDensity) {
                println(s"Starting Density $curDensity")
                monitor ! AddNetworks(
                    agentTypeCount = Array((SilenceStrategyType.Majority, SilenceEffectType.Memory, numberOfAgents)),
                    agentBiases = Array((CognitiveBiasType.DeGroot, 1.0f)),
                    distribution = Uniform,
                    saveMode = Agentless, //NeighborlessMode(Roundless) Agentless StandardLight
                    recencyFunction = None,
                    numberOfNetworks = numberOfNetworks,
                    density = curDensity,
                    iterationLimit = 1000,
                    degreeDistribution = 2.5f,
                    stopThreshold = 0.001f
                    )
            }
            
    }
}
