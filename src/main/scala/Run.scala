import akka.actor.{Actor, ActorRef, Props}

import scala.collection.mutable.ArrayBuffer

import java.util.UUID

import cats.effect.unsafe.implicits.global

// Mesagges

case object StartRun // Monitor -> Run
case class BuildingComplete(networkId: UUID) // Network -> Run
case class RunningComplete(networkId: UUID) // Network -> Run
case class ChangeAgentLimit(numberOfAgents: Int) // Monitor -> Run

// Actor ToDo create logs
class Run private extends Actor {
    var runId: Option[Int] = Some(-1)
    // ToDo use arrays instead
    var networks = ArrayBuffer[ActorRef]()
    
    // Timing
    val globalTimers = new CustomMultiTimer
    val buildingTimers = new CustomMultiTimer
    val runningTimers = new CustomMultiTimer
    
    // Limits
    var agentLimit = 100000
    
    // Batches
    var batches = 0
    var networksPerBatch = 0
    
    //
    var numberOfNetworks = 0
    var numberOfAgents = 0
    
    // counts
    var networksBuilt = 0
    var numberOfNetworksFinished = 0
    
    
    // Run a specific network
    def this(agentLimit: Int,
             agents: Array[AgentInitialState],
             stopThreshold: Float,
             iterationLimit: Int,
             name: String
            ) = {
        this()
        this.agentLimit = agentLimit
        runId = DatabaseManager.createRun(
            1, None, None, stopThreshold, iterationLimit, CustomDistribution.toString, None, None
        )
        globalTimers.start(s"Total_time")
        
        globalTimers.start("Building")
        val networkId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
        val network = context.actorOf(Props(new Network(
            networkId,
            agents.length,
            stopThreshold = stopThreshold,
            monitor = self,
            iterationLimit = iterationLimit,
            agentTypeCount = None
        )), name)
        DatabaseManager.createNetwork(networkId, name, runId.get, agents.length)
        networks += network
        numberOfAgents = agents.length
        numberOfNetworks = 1
        calculateBatches(1, agents.length)
        buildingTimers.start(network.path.name)
        network ! BuildCustomNetwork(agents)
        
    }
    
    // Run generated networks
    def this(agentLimit: Int,
             numberOfNetworks: Int,
             numberOfAgents: Int,
             density: Int,
             degreeDistribution: Float,
             stopThreshold: Float,
             distribution: Distribution,
             iterationLimit: Int,
             agentTypeCount: Map[AgentType, Int]
            ) = {
        this()
        globalTimers.start(s"Total_time")
        this.agentLimit = agentLimit
        this.numberOfNetworks = numberOfNetworks
        this.numberOfAgents = numberOfAgents
        runId = DatabaseManager.createRun(
            numberOfNetworks, Some(density), Some(degreeDistribution), stopThreshold, iterationLimit,
            distribution.toString, None, None
        )
        
        this.numberOfNetworks = numberOfNetworks
        globalTimers.start("Building")
        for (i <- 0 until numberOfNetworks) {
            val networkId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
            networks += context.actorOf(Props(new Network(
                networkId,
                numberOfAgents,
                density,
                degreeDistribution,
                stopThreshold,
                distribution,
                self,
                iterationLimit,
                Some(agentTypeCount)
            )), s"N${i + 1}")
            DatabaseManager.createNetwork(networkId, s"N${i + 1}", runId.get, numberOfAgents)
        }
    }
    
    // Message handling
    def receive: Receive = {
        case StartRun =>
            calculateBatches(numberOfNetworks, numberOfAgents)
            buildNetworkBatch()
            
        case BuildingComplete(networkId) =>
            val network = sender()
            val networkName = network.path.name
            networksBuilt += 1
            if (networksBuilt == 1) globalTimers.start("Running")
            
            DatabaseManager.updateTimeField(Right(networkId), buildingTimers.stop(networkName, msg = " building"), "networks","build_time")
            if (networksBuilt == networks.size)
                DatabaseManager.updateTimeField(Left(runId.get), globalTimers.stop("Building"), "runs", "build_time")
            
            runningTimers.start(networkName)
            network ! RunNetwork
        
        case RunningComplete(networkId) =>
            val network = sender()
            val networkName = network.path.name
            numberOfNetworksFinished += 1
            DatabaseManager.updateTimeField(Right(networkId), runningTimers.stop(networkName, msg = " running"), "networks", "run_time")
            if (numberOfNetworksFinished == numberOfNetworks) {
                DatabaseManager.updateTimeField(Left(runId.get), globalTimers.stop("Running"), "runs", "run_time")
                println("Saving to DB...")
                RoundDataRouters.saveRemainingData()
                context.parent ! RunComplete
                context.stop(self)
            } else if (numberOfNetworksFinished % networksPerBatch == 0) {
                calculateBatches(numberOfNetworks, numberOfAgents)
                buildNetworkBatch()
            }
        
        case ChangeAgentLimit(newAgentLimit: Int) =>
            agentLimit = newAgentLimit
            
    }
    
    private def calculateBatches(numberOfNetworks: Int, numberOfAgents: Int): Unit = {
        if (numberOfAgents >= agentLimit) {
            batches = numberOfNetworks
            networksPerBatch = 1
        } else {
            networksPerBatch = agentLimit / numberOfAgents
            networksPerBatch = math.min(networksPerBatch, numberOfNetworks)
            batches = math.ceil(numberOfNetworks.toDouble / networksPerBatch).toInt
        }
    }
    
    private def buildNetworkBatch(): Unit = {
        for (i <- numberOfNetworksFinished until networksPerBatch + numberOfNetworksFinished) {
            if (i < networks.size)
                buildingTimers.start(networks(i).path.name)
                networks(i) ! BuildNetwork
        }
    }
}
