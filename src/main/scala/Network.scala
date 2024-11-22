import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout

import scala.concurrent.duration.*
import scala.collection.mutable
import cats.effect.unsafe.implicits.global

import java.util.UUID
// Network

// Messages
case class BuildCustomNetwork(agents: Array[AgentInitialState]) // Monitor -> network
case object BuildNetwork // Monitor -> network
case class BuildNetworkByGroups(groups: Int)
case object RunNetwork // Monitor -> network
case object RunFirstRound // AgentStaticDataSaver -> Network


case class AgentUpdated(hasNextIter: Boolean, belief: Float, isStable: Boolean, updatedBelief: Boolean) // Agent -> network
case object SaveRemainingData // Network -> AgentRoundDataSaver


case object AgentFinished // Agent -> Network

// Actor
class Network(networkId: UUID, numberOfAgents: Int, density: Int = -1, degreeDistributionParameter: Float = -0.1f,
              stopThreshold: Float, distribution: Distribution = CustomDistribution, monitor: ActorRef,
              iterationLimit: Int, agentTypeCount: Option[Map[AgentType, Int]]) extends Actor {
    // Agents
    val agents: Array[ActorRef] = Array.ofDim[ActorRef](numberOfAgents)
    val bimodal = new BimodalDistribution(0.25, 0.75)
    
    // Round state
    var round: Int = 0
    var pendingResponses: Int = 0
    var minBelief: Float = 2.0f
    var maxBelief: Float = -1.0f
    var shouldUpdate: Boolean = false
    var shouldContinue: Boolean = false
    
    // Data saving
    val networkSaver: ActorRef = context.actorOf(Props(
        new NetworkStructureSaver(numberOfAgents)
    ), name = s"NetworkSaverOf${self.path.name}")
    
    val agentStaticDataSaver: ActorRef = context.actorOf(Props(
        new AgentStaticDataSaver(numberOfAgents)
    ), name = s"StaticA_${self.path.name}")
    
    // Limits
    implicit val timeout: Timeout = Timeout(600.seconds)

    
    def receive: Receive = building
    
    // Building state
    private def building: Receive = {
        case BuildCustomNetwork(agents) =>
            val mask: Array[(ActorRef, String)] = Array.ofDim(agents.length)
            
            for (i <- agents.indices) {
                val agentId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                val newAgent = createNewAgent(agentId, agentId.toString, agents(i).agentType)
                this.agents(i) = newAgent
                
                mask(i) = (newAgent, agents(i).name)
                //println(s"${agents(i).name}, ${agents(i).neighbors.mkString("Array(", ", ", ")")}")
                newAgent ! SetInitialState(agents(i).initialBelief, agents(i).tolerance, agents(i).name)
            }
       
            for (i <- agents.indices) {
                agents(i).neighbors.foreach(
                    (neighborName, neighborInfluence) =>
                        val neighborActor: ActorRef = mask.find(_._2 == neighborName).get._1
                        this.agents(i) ! SetNeighborInfluence(neighborActor, neighborInfluence)
                )
            }
            
            context.become(running)
            monitor ! BuildingComplete(networkId)
        
        case BuildNetwork =>
            val fenwickTree = new FenwickTree(numberOfAgents, density, degreeDistributionParameter - 2)
            val agentPicker = agentTypePicker(agentTypeCount.get)
            
            // Initialize the first n=density agents
            for (i <- 0 until density) {
                val agentId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                val agentType = agentPicker.next()
                val newAgent = createNewAgent(agentId, agentId.toString, agentType)
                agents(i) = newAgent
                for (j <- 0 until i) {
                    agents(j) ! AddToNeighborhood(newAgent)
                    newAgent ! AddToNeighborhood(agents(j))
                }
            }
            
            // Create and link the agents
            for (i <- density - 1 until numberOfAgents - 1) {
                // Create the new agent
                val agentId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                val agentType = agentPicker.next()
                val newAgent = createNewAgent(agentId, agentId.toString, agentType)
                agents(i + 1) = newAgent
                
                // Pick the agents based on their atractiveness score and link them
                val agentsPicked = fenwickTree.pickRandoms()
                agentsPicked.foreach { agent =>
                    agents(agent) ! AddToNeighborhood(newAgent)
                    newAgent ! AddToNeighborhood(agents(agent))
                }
            }
            context.become(running)
            monitor ! BuildingComplete(networkId)
        
        case BuildNetworkByGroups(numberOfGroups) =>
        
    }
    
    
    // Running State
    private def running: Receive = {
        case RunNetwork =>
            // agents.foreach { agent => agent ! SaveAgentStaticData }
            var i = 0
            while (i < agents.length) {
                agents(i) ! SaveAgentStaticData
                i += 1
            }
        
        case RunFirstRound =>
            runRound()
            
        case AgentUpdated(hasNextIter, belief, isStable, updatedBelief) =>
            pendingResponses -= 1
            if (hasNextIter) shouldUpdate = true
            if (!isStable) shouldContinue = true
            //println(s"${sender().path.name}, Round:$round, Speaking:$hasNextIter, ")
            maxBelief = math.max(maxBelief, belief)
            minBelief = math.min(minBelief, belief)
            if (pendingResponses == 0) {
                // if (round % 50 == 0) println(s"Round $round")
                round += 1
               
                if ((maxBelief - minBelief) < stopThreshold) {
                    monitor ! RunningComplete(networkId)
                    DatabaseManager.updateNetworkFinalRound(networkId, round, true)
                    agents.foreach { agent => agent ! SnapShotAgent }
                    pendingResponses = agents.length
                }
                else if (round == iterationLimit || (!shouldContinue && updatedBelief)) {
                    monitor ! RunningComplete(networkId)
                    DatabaseManager.updateNetworkFinalRound(networkId, round, false)
                    agents.foreach { agent => agent ! SnapShotAgent }
                    pendingResponses = agents.length
                } else {
                    runRound()
                    minBelief = 2.0
                    maxBelief = -1.0
                }
            }
        
        case AgentFinished =>
            pendingResponses -= 1
            if (pendingResponses == 0) {
                context.stop(self)
            }
    }
    
    private def runRound(): Unit = {
        val forceUpdate = shouldUpdate
        if (forceUpdate) {
            var i = 0
            while (i < agents.length) {
                agents(i) ! UpdateAgent
                i += 1
            }
            // agents.foreach { agent => agent !  UpdateAgent}
        }
        else {
            var i = 0
            while (i < agents.length) {
                agents(i) ! UpdateAgentForce
                i += 1
            }
            // agents.foreach { agent => agent !  UpdateAgentForce}
        }
        shouldUpdate = false
        shouldContinue = false
        pendingResponses = agents.length
        
    }
    
    
    // Functions:
    private def createNewAgent(agentId: UUID, agentName: String, agentType: AgentType): ActorRef = {
        val agentClass: Class[? <: DeGrootianAgent] = agentType match {
            case MemoryLessConfidence => classOf[MemLessConfidenceAgent]
            case MemoryConfidence => classOf[MemoryConfidenceAgent]
            case MemoryLessMajority => classOf[MemLessMajorityAgent]
            case MemoryMajority => classOf[MemoryMajorityAgent]
        }
        
        context.actorOf(Props(
            agentClass,
            agentId, stopThreshold, distribution, networkSaver, agentStaticDataSaver, networkId
        ), agentName)
    }
    
    private def agentTypePicker(initialCounts: Map[AgentType, Int]): Iterator[AgentType] = new Iterator[AgentType] {
        private val agentTypeCount: mutable.Map[AgentType, Int] = mutable.Map(initialCounts.toSeq*)
        private val keys: Seq[AgentType] = agentTypeCount.keys.toSeq
        private var index: Int = 0
        
        override def hasNext: Boolean = agentTypeCount.exists(_._2 > 0)
        
        override def next(): AgentType = {
            if (!hasNext) throw new NoSuchElementException("No more agents to pick")
            
            // Find the next non-zero agent type
            while (agentTypeCount(keys(index)) == 0) {
                index = (index + 1) % keys.length
            }
            
            val agentType = keys(index)
            agentTypeCount(agentType) -= 1
            
            // Move to the next index
            index = (index + 1) % keys.length
            
            agentType
        }
    }
}
