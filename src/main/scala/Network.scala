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
case object RunFirstRound // AgentStaticDataSaver -> Network


case class AgentUpdated(belief: Float, isStable: Boolean) // Agent -> network
case object SaveRemainingData // Network -> AgentRoundDataSaver


case object AgentFinished // Agent -> Network

// Agent types

// Actor
class Network(networkId: UUID,
              runMetadata: RunMetadata,
              agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
              agentBiases: Array[(CognitiveBiasType, Float)]) extends Actor {
    // Agents
    val agents: Array[ActorRef] = Array.ofDim[ActorRef](runMetadata.agentsPerNetwork)
    val bimodal = new BimodalDistribution(0.25, 0.75)
    
    // Belief buffers
    val beliefBuffer1: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    val beliefBuffer2: Array[Float] = Array.fill(runMetadata.agentsPerNetwork)(-1f)
    
    val speakingBuffer1: Array[Boolean] = Array.fill(runMetadata.agentsPerNetwork)(true)
    val speakingBuffer2: Array[Boolean] = Array.fill(runMetadata.agentsPerNetwork)(true)
    
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
            val agentMap = new java.util.HashMap[String, (ActorRef, Int)](agents.length)
            
            for (i <- agents.indices) {
                val agentId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                val newAgent = createNewAgent(
                    agentId,
                    agents(i).silenceStrategy,
                    agents(i).silenceEffect,
                    i,
                    agentId.toString
                )
                this.agents(i) = newAgent
                
                agentMap.put(agents(i).name, (newAgent, i))
                
                newAgent ! SetInitialState(
                    agents(i).name,
                    agents(i).initialBelief,
                    agents(i).toleranceRadius,
                    agents(i).toleranceOffset
                    )
            }
            
            var i = 0
            while (i < neighbors.length) {
                val neighbor = neighbors(i)
                val source = agentMap.get(neighbor.source)._1
                val target = agentMap.get(neighbor.target)._2
                
                source ! SetNeighborInfluence(target, neighbor.influence, neighbor.bias)
                i += 1
            }
            
            context.become(running)
            context.parent ! BuildingComplete(networkId)
        
        case BuildNetwork =>
            val fenwickTree = new FenwickTree(
                runMetadata.agentsPerNetwork,
                runMetadata.optionalMetaData.get.density.get,
                runMetadata.optionalMetaData.get.degreeDistribution.get - 2)
            val pickStack = createShuffledStack(runMetadata.agentsPerNetwork)

            
            // Initialize the first n=density agents
            for (i <- 0 until runMetadata.optionalMetaData.get.density.get) {
                val agentId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                val agentType = chooseAgentType(pickStack.pop())
                val newAgent = createNewAgent(agentId, agentType._1, agentType._2, i, agentId.toString)
                agents(i) = newAgent
                for (j <- 0 until i) {
                    agents(j) ! AddNeighbor(i, CognitiveBiasType.DeGroot)
                    newAgent ! AddNeighbor(j, CognitiveBiasType.DeGroot)
                }
            }
            
            // Create and link the agents
            for (i <- runMetadata.optionalMetaData.get.density.get - 1 until runMetadata.agentsPerNetwork - 1) {
                val agentId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                val agentType = chooseAgentType(pickStack.pop())
                val newAgent = createNewAgent(agentId, agentType._1, agentType._2, i + 1, agentId.toString)
                agents(i + 1) = newAgent
                
                val agentsPicked = fenwickTree.pickRandoms()
                // ToDo convert to while loop
                agentsPicked.foreach { agent =>
                    agents(agent) ! AddNeighbor(i, CognitiveBiasType.DeGroot)
                    newAgent ! AddNeighbor(agent, CognitiveBiasType.DeGroot)
                }
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
                agents(i) ! FirstUpdate(neighborSaver, agentStaticDataSaver, beliefBuffer1, agents)
                i += 1
            }
        
        case RunFirstRound =>
            pendingResponses -= 1
            if (pendingResponses == 0) {
                round += 1
                runRound()
                pendingResponses = agents.length
            }
            
        case AgentUpdated(belief, isStable) =>
            pendingResponses -= 1
            if (!isStable) shouldContinue = true
            maxBelief = math.max(maxBelief, belief)
            minBelief = math.min(minBelief, belief)
            if (pendingResponses == 0) {
                // if (round % 50 == 0) println(s"Round $round")
                round += 1
//                println(beliefBuffer1.mkString("B1 Array(", ", ", ")"))
//                println(beliefBuffer2.mkString("B2 Array(", ", ", ")"))
//                Thread.sleep(1000)
//                println(s"Round $round complete \n" +
//                  s"con1: ${(maxBelief - minBelief) < runMetadata.stopThreshold}\n" +
//                  s"con2: ${round == runMetadata.iterationLimit || !shouldContinue}"
//                )
                if ((maxBelief - minBelief) < runMetadata.stopThreshold) {
                    context.parent ! RunningComplete(networkId)
                    if (runMetadata.saveMode.includesNetworks) DatabaseManager.updateNetworkFinalRound(networkId, round, true)
                    if (runMetadata.saveMode.includesLastRound) agents.foreach { agent => agent ! SnapShotAgent }
                }
                else if (round == runMetadata.iterationLimit || !shouldContinue) {
                    context.parent ! RunningComplete(networkId)
                    if (runMetadata.saveMode.includesNetworks) DatabaseManager.updateNetworkFinalRound(networkId, round, false)
                    if (runMetadata.saveMode.includesLastRound) agents.foreach { agent => agent ! SnapShotAgent }
                } else {
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
        val msg = if (bufferSwitch) UpdateAgent(beliefBuffer1, beliefBuffer2, speakingBuffer1, speakingBuffer2)
                  else UpdateAgent(beliefBuffer2, beliefBuffer1, speakingBuffer2, speakingBuffer1)
        while (i < agents.length) {
            agents(i) ! msg
            i += 1
        }
        // agents.foreach { agent => agent !  UpdateAgent}
        // agents.foreach { agent => agent !  UpdateAgentForce}
        shouldContinue = false
        bufferSwitch = !bufferSwitch
    }
    
    
    // Functions:
    private def createNewAgent(agentId: UUID, silenceStrategyType: SilenceStrategyType,
                               silenceEffectType: SilenceEffectType, agentIndex: Int, agentName: String): ActorRef = {
        val silenceStrategy = SilenceStrategyFactory.create(silenceStrategyType)
        val silenceEffect = SilenceEffectFactory.create(silenceEffectType)
        
        context.actorOf(Props(
            new Agent(agentId, silenceStrategy, silenceEffect, runMetadata, agentIndex)
        ), agentName)
    }
    
    private def createShuffledStack(n: Int): mutable.Stack[Int] = {
        val stack = mutable.Stack[Int]()
        stack.pushAll(Random.shuffle(1 to n))
        stack
    }
    
    private def chooseAgentType(number: Int): (SilenceStrategyType, SilenceEffectType) = {
        var i: Int = 0
        while (i <= agentTypeCount.length) {
            if (number <= agentTypeCount(i)._3) {
                return (agentTypeCount(i)._1, agentTypeCount(i)._2)
            }
            i += 1
        }
        (agentTypeCount(i)._1, agentTypeCount(i)._2)
    }
    
}
