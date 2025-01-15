import akka.actor.{Actor, ActorRef, Props}

import java.util.UUID

import cats.effect.unsafe.implicits.global

// Mesagges

case object StartRun // Monitor -> Run
case class BuildingComplete(networkId: UUID) // Network -> Run
case class RunningComplete(networkId: UUID) // Network -> Run
case class ChangeAgentLimit(numberOfAgents: Int) // Monitor -> Run

// Actor ToDo create logs
class Run extends Actor {
    // Collections
    var networks: Array[ActorRef] = null
    var agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)] = null
    var agentBiases: Array[(CognitiveBiasType, Float)] = null
    
    // Timing
    val globalTimers = new CustomMultiTimer
    val buildingTimers = new CustomMultiTimer
    val runningTimers = new CustomMultiTimer
    
    // Batches
    var batches = 0
    var networksPerBatch = 0
    
    // counts
    var networksBuilt = 0
    var numberOfNetworksFinished = 0
    
    //
    var runMetadata: RunMetadata = null
    
    // Run a specific network
    def this(
        runMetadata: RunMetadata,
        agents: Array[AgentInitialState],
        neighbors: Array[Neighbors],
        name: String
    ) = {
        this()
        
        globalTimers.start(s"Total_time")
        runMetadata.runId = DatabaseManager.createRun(
            runMetadata.runMode,
            runMetadata.saveMode,
            1,
            None,
            None,
            runMetadata.stopThreshold,
            runMetadata.iterationLimit,
            CustomDistribution.toString
            )
        
        this.runMetadata = runMetadata
        globalTimers.start("Building")
        val networkId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
        val network = context.actorOf(Props(new Network(
            networkId,
            runMetadata,
            null,
            null
            )), name)
        if (runMetadata.saveMode.includesNetworks)
            DatabaseManager.createNetwork(networkId, name, runMetadata.runId.get, agents.length)
        networks = Array(network)
        
        calculateBatches()
        buildingTimers.start(network.path.name)
        network ! BuildCustomNetwork(agents, neighbors)
        
    }
    
    // Run generated networks
    def this(runMetadata: RunMetadata,
             agentTypeCount: Array[(SilenceStrategyType, SilenceEffectType, Int)],
             agentBiases: Array[(CognitiveBiasType, Float)]
            ) = {
        this()
        globalTimers.start(s"Total_time")
        this.runMetadata = runMetadata
        this.agentTypeCount = agentTypeCount
        this.agentBiases = agentBiases
        
        runMetadata.runId = DatabaseManager.createRun(
            runMetadata.runMode,
            runMetadata.saveMode,
            runMetadata.numberOfNetworks,
            runMetadata.optionalMetaData.get.density,
            runMetadata.optionalMetaData.get.degreeDistribution,
            runMetadata.stopThreshold,
            runMetadata.iterationLimit,
            runMetadata.distribution.toString
        )
        networks = Array.fill[ActorRef](runMetadata.numberOfNetworks + 1)(null)
        
    }
    
    // Message handling
    def receive: Receive = {
        case StartRun =>
            calculateBatches()
            buildNetworkBatch()
            
        case BuildingComplete(networkId) =>
            val network = sender()
            val networkName = network.path.name
            networksBuilt += 1
            if (networksBuilt == 1) globalTimers.start("Running")
            
            if (runMetadata.saveMode.includesNetworks) {
                DatabaseManager.updateTimeField(Right(networkId), buildingTimers.stop(networkName, msg = " building"),
                    "networks", "build_time")
            }
            
            if (networksBuilt == networks.length) {
                DatabaseManager.updateTimeField(Left(runMetadata.runId.get), globalTimers.stop("Building"), "runs",
                    "build_time")
            }
            
            runningTimers.start(networkName)
            network ! RunNetwork
        
        case RunningComplete(networkId) =>
            val network = sender()
            val networkName = network.path.name
            numberOfNetworksFinished += 1
            
            if (runMetadata.saveMode.includesNetworks) {
                DatabaseManager.updateTimeField(Right(networkId), runningTimers.stop(networkName, msg = " running"),
                    "networks", "run_time")
            }
            
            if (numberOfNetworksFinished == runMetadata.numberOfNetworks) {
                DatabaseManager.updateTimeField(Left(runMetadata.runId.get), globalTimers.stop("Running"), "runs", "run_time")
                RoundRouter.saveRemainingData()
                context.parent ! RunComplete
                context.stop(self)
            } else if (numberOfNetworksFinished % networksPerBatch == 0) {
                calculateBatches()
                buildNetworkBatch()
            }
        
        case ChangeAgentLimit(newAgentLimit: Int) =>
            
    }
    
    // ToDo coordinate for global agent limit
    private def calculateBatches(): Unit = {
        if (runMetadata.agentsPerNetwork >= runMetadata.agentLimit) {
            batches = runMetadata.numberOfNetworks
            networksPerBatch = 1
        } else {
            networksPerBatch = runMetadata.agentLimit / runMetadata.agentsPerNetwork
            networksPerBatch = math.min(networksPerBatch, runMetadata.numberOfNetworks)
            batches = math.ceil(runMetadata.numberOfNetworks.toDouble / networksPerBatch).toInt
        }
    }
    
    private def buildNetworkBatch(): Unit = {
        if (networksBuilt == 0) globalTimers.start("Building")
        
        // 0-99 -> 100-200 -> 200-300
        // 1-100 -> 101-200 -> 201-300 -> 301-400
        for (i <- networksBuilt until networksPerBatch + networksBuilt) {
            val index = i + 1
            if (index < networks.length)
                val networkId: UUID = UUIDGenerator.generateUUID().unsafeRunSync()
                networks(index) = context.actorOf(Props(new Network(
                    networkId,
                    runMetadata,
                    agentTypeCount,
                    agentBiases
                )), s"N$index")
                
                if (runMetadata.saveMode.includesNetworks) {
                    DatabaseManager.createNetwork(networkId, s"N$index", runMetadata.runId.get,
                        runMetadata.agentsPerNetwork)
                }
                
                buildingTimers.start(networks(index).path.name)
                networks(index) ! BuildNetwork
        }
    }
}
