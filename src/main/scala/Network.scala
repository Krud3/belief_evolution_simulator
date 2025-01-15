import SilenceStrategyType.DeGroot
import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout

import scala.concurrent.duration.*
import scala.collection.mutable
import scala.collection.mutable.Stack
import cats.effect.unsafe.implicits.global

import java.util.UUID
import scala.util.Random
// Network

// Messages
case class BuildCustomNetwork(
    agents: Array[AgentInitialState],
    neighbors: Array[Neighbors]
) // Monitor -> network

case object BuildNetwork // Monitor -> network
case class BuildNetworkByGroups(groups: Int)
case object RunNetwork // Monitor -> network
case object RunFirstRound // Agent -> Network


case class AgentUpdated(maxBelief: Float, minBelief: Float, isStable: Boolean) // Agent -> network
case object SaveRemainingData // Network -> AgentRoundDataSaver


case object AgentFinished // Agent -> Network

// Agent types

// Actor
class Network(networkId: UUID,
              runMetadata: RunMetadata,
              agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
              agentBiases: Array[(CognitiveBiasType, Float)]) extends Actor {
    // Agents
    val numberOfAgentActors: Int = (runMetadata.agentsPerNetwork + 31) / 32
    val agentsPerActor: Array[Int] = new Array[Int](numberOfAgentActors)
    calculateAgentsPerActor() // fill agents per actor
    val bucketStart: Array[Int] = new Array[Int](numberOfAgentActors)
    calculateCumSum() // Fill buckets
    val agents: Array[ActorRef] = Array.ofDim[ActorRef](numberOfAgentActors)
    val agentsIds: Array[UUID] = Array.ofDim[UUID](runMetadata.agentsPerNetwork)
    val bimodal = new BimodalDistribution(0.25, 0.75)
    
    // Belief buffers
    val beliefBuffer1: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    val beliefBuffer2: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    
    val speakingBuffer1: AgentStates = AgentStates(runMetadata.agentsPerNetwork)
    val speakingBuffer2: AgentStates = AgentStates(runMetadata.agentsPerNetwork)
    
    // Data saving
    val neighborSaver: ActorRef = context.actorOf(Props(
        new NeighborSaver(runMetadata.agentsPerNetwork)
        ), name = s"NeighborSaver${self.path.name}")
    
    val agentStaticDataSaver: ActorRef = context.actorOf(Props(
        new AgentStaticDataSaver(runMetadata.agentsPerNetwork, networkId)
        ), name = s"StaticSaver_${self.path.name}")
    
    // Limits
    implicit val timeout: Timeout = Timeout(600.seconds)
    
    // Round state
    var round: Int = 0
    var pendingResponses: Int = 0
    var minBelief: Float = 2.0f
    var maxBelief: Float = -1.0f
    var shouldUpdate: Boolean = false
    var shouldContinue: Boolean = false
    var bufferSwitch: Boolean = true
    
    def receive: Receive = building
    
    // Building state
    private def building: Receive = {
        case BuildCustomNetwork(agents, neighbors) =>
            val agentMap = new java.util.HashMap[String, Int](agents.length)
            var i = 0
            var j = 0
            while (i < agentsPerActor.length) {
                val silenceStrategy: Array[SilenceStrategy] = new Array[SilenceStrategy](agentsPerActor(i))
                val silenceEffect: Array[SilenceEffect] = new Array[SilenceEffect](agentsPerActor(i))
                val names: Array[String] = new Array[String](agentsPerActor(i))
                
                while (j < agentsPerActor(i)) {
                    agentsIds(j + bucketStart(i)) = UUIDGenerator.generateUUID().unsafeRunSync()
                    silenceStrategy(j) = SilenceStrategyFactory.create(agents(j).silenceStrategy)
                    silenceEffect(j) = SilenceEffectFactory.create(agents(j).silenceEffect)
                    names(j) = agents(j).name
                    agentMap.put(agents(j).name, j + bucketStart(i))
                    j += 1
                }
                val index = i
                this.agents(i) = context.actorOf(Props(
                    new Agent(
                        agentsIds,
                        silenceStrategy, silenceEffect,
                        runMetadata,
                        beliefBuffer1, beliefBuffer2,
                        speakingBuffer1, speakingBuffer2,
                        agentsPerActor(index), bucketStart(index),
                        names
                        )
                    ), s"${self.path.name}_A$i")
                j = 0
                while (j < agentsPerActor(i)) {
                    this.agents(i) ! SetInitialState(agents(j).initialBelief, agents(j).toleranceRadius,
                                                     agents(j).toleranceOffset, j)
                     j += 1
                }
                i += 1
            }
            i = 0
            while (i < neighbors.length) {
                val neighbor = neighbors(i)
                val source: Int = agentMap.get(neighbor.source)
                val target: Int = agentMap.get(neighbor.target)
                getAgentActor(source) ! SetNeighborInfluence(source, target, neighbor.influence, neighbor.bias)
                i += 1
            }
            
            context.become(running)
            context.parent ! BuildingComplete(networkId)
        
        case BuildNetwork =>
            val fenwickTree = new FenwickTree(
                runMetadata.agentsPerNetwork,
                runMetadata.optionalMetaData.get.density.get,
                runMetadata.optionalMetaData.get.degreeDistribution.get - 2)
            
            // Create the Actors
            val agentsRemaining: Array[Int] = agentTypeCount.map(_._3)
            var agentRemainingCount = runMetadata.agentsPerNetwork
            var i = 0
            while (i < agentsPerActor.length) {
                val silenceStrategy: Array[SilenceStrategy] = new Array[SilenceStrategy](agentsPerActor(i))
                val silenceEffect: Array[SilenceEffect] = new Array[SilenceEffect](agentsPerActor(i))
                
                // Get the proportion of agents
                val agentTypes = getNextBucketDistribution(agentsRemaining, agentsPerActor(i), agentRemainingCount)
                
                // Set the agent types
                var j = 0
                var k = 0
                while (j < agentTypes.length) {
                    while (k < agentTypes(j)) {
                        agentsIds(k + bucketStart(i)) = UUIDGenerator.generateUUID().unsafeRunSync()
                        silenceStrategy(k) = SilenceStrategyFactory.create(agentTypeCount(j)._1)
                        silenceEffect(k) = SilenceEffectFactory.create(agentTypeCount(j)._2)
                        k += 1
                    }
                    j += 1
                }
                agentRemainingCount -= agentsPerActor(i)
                // Create the agent actor
                val index = i
                agents(i) = context.actorOf(Props(
                    new Agent(agentsIds, silenceStrategy, silenceEffect, runMetadata, beliefBuffer1, beliefBuffer2,
                              speakingBuffer1, speakingBuffer2, agentsPerActor(index), bucketStart(index), null)
                    ), s"${self.path.name}_A$i")
                
                i += 1
            }
            
            // Initialize the first n=density agents
            i = 0
            while (i < runMetadata.optionalMetaData.get.density.get) {
                for (j <- 0 until i) {
                    getAgentActor(i) ! AddNeighbor(i, j, CognitiveBiasType.DeGroot)
                    getAgentActor(j) ! AddNeighbor(j, i, CognitiveBiasType.DeGroot)
                }
                i += 1
            }
            
            // Create and link the rest of the agents
            while (i < runMetadata.agentsPerNetwork) {
                val agentsPicked = fenwickTree.pickRandoms()
                var j = 0
                while (j < agentsPicked.length) {
                    getAgentActor(i) ! AddNeighbor(i, agentsPicked(j), CognitiveBiasType.DeGroot)
                    getAgentActor(agentsPicked(j)) ! AddNeighbor(agentsPicked(j), i, CognitiveBiasType.DeGroot)
                    j += 1
                }
                i += 1
            }
            context.become(running)
            context.parent ! BuildingComplete(networkId)
        
        case BuildNetworkByGroups(numberOfGroups) =>
        
    }
    
    // Running State
    private def running: Receive = {
        case RunNetwork =>
            // agents.foreach { agent => agent ! SaveAgentStaticData }
            pendingResponses = agents.length
            var i = 0
            while (i < agents.length) {
                agents(i) ! FirstUpdate(neighborSaver, agentStaticDataSaver, agents)
                i += 1
            }
        
        case RunFirstRound =>
            pendingResponses -= 1
            if (pendingResponses == 0) {
                round += 1
                runRound()
                pendingResponses = agents.length
            }
            
        case AgentUpdated(maxActorBelief, minActorBelief, isStable) => 
            pendingResponses -= 1
            // If isStable true then we don't continue as we are stable
            if (!isStable) shouldContinue = true
            maxBelief = math.max(maxBelief, maxActorBelief)
            minBelief = math.min(minBelief, minActorBelief)
            if (pendingResponses == 0) {
                if ((maxBelief - minBelief) < runMetadata.stopThreshold) {
                    println(s"Final round: $round")
                    context.parent ! RunningComplete(networkId)
                    if (runMetadata.saveMode.includesNetworks) DatabaseManager.updateNetworkFinalRound(networkId, round, true)
                    if (runMetadata.saveMode.includesLastRound) agents.foreach { agent => agent ! SnapShotAgent }
                }
                else if (round == runMetadata.iterationLimit || !shouldContinue) {
                    println(s"Final round: $round")
                    context.parent ! RunningComplete(networkId)
                    if (runMetadata.saveMode.includesNetworks) DatabaseManager.updateNetworkFinalRound(networkId, round, false)
                    if (runMetadata.saveMode.includesLastRound) agents.foreach { agent => agent ! SnapShotAgent }
                } else {
                    round += 1
                    runRound()
                    minBelief = 2.0
                    maxBelief = -1.0
                }
                pendingResponses = agents.length
            }
        
        case AgentFinished =>
            pendingResponses -= 1
            if (pendingResponses == 0) {
                context.stop(self)
            }
    }
    
    private def runRound(): Unit = {
        var i = 0
        val msg = if (bufferSwitch) UpdateAgent1R else UpdateAgent2R
        while (i < agents.length - 3) {
            agents(i) ! msg
            agents(i + 1) ! msg
            agents(i + 2) ! msg
            agents(i + 3) ! msg
            i += 4
        }
        
        while (i < agents.length) {
            agents(i) ! msg
            i += 1
        }
        shouldContinue = false
        bufferSwitch = !bufferSwitch
    }
    
    
    // Functions:
    def getNextBucketDistribution(agentsRemaining: Array[Int], bucketSize: Int, totalAgentsRemaining: Int): Array[Int] = {
        val result = new Array[Int](agentsRemaining.length)
        val floatPart = new Array[Double](agentsRemaining.length)
        var i = 0
        while (i < agentsRemaining.length) {
            val fullResult = (agentsRemaining(i) * bucketSize).toDouble / totalAgentsRemaining
            val intPart = math.floor(fullResult).toInt
            val decimalPart = fullResult - intPart
            result(i) = intPart
            agentsRemaining(i) -= intPart
            floatPart(i) = decimalPart
            i += 1
        }
        
        val remainder = floatPart.zipWithIndex.sortBy(-_._1)
        val missing = math.round(floatPart.sum).toInt
        i = 0
        while (i < missing) {
            result(remainder(i)._2) += 1
            agentsRemaining(remainder(i)._2) -= 1
            i += 1
        }
        
        result
    }
    
    @inline def calculateAgentsPerActor(): Unit = {
        var i = 0
        var remainingToAssign = runMetadata.agentsPerNetwork
        while (0 < remainingToAssign) {
            agentsPerActor(i) += math.min(8, remainingToAssign)
            remainingToAssign -= 8
            i = (i + 1) % numberOfAgentActors
        }
    }
    
    @inline def calculateCumSum(): Unit = {
        for (i <- 1 until numberOfAgentActors)
            bucketStart(i) = agentsPerActor(i - 1) + bucketStart(i - 1)
    }
    
    @inline def getAgentActor(index: Int): ActorRef = {
        var i = 0
        while (i < bucketStart.length - 1) {
            if (index < bucketStart(i + 1)) return agents(i)
            i += 1
        }
        agents(i)
    }
    
}
