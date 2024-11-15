import akka.actor.{Actor, ActorRef, Props}

import scala.collection.mutable.ArrayBuffer

// Monitor


// Messages
case class CreateNetwork // Monitor -> Network
(
  numberOfAgents: Int,
  density: Int,
  stopThreshold: Float,
  degreeDistributionParameter: Float,
  distribution: Distribution
)

case object RunComplete // Monitor -> Run

// Actor
class Monitor extends Actor {
    // Limits
    val agentLimit: Int = 100000
    var currentUsage: Int = agentLimit
    
    // Router
    val totalCores: Int = Runtime.getRuntime.availableProcessors()
    val saveThreshold: Int = 1500000
    RoundDataRouters.createRouters(context, totalCores, saveThreshold)
    
    // Runs
    var activeRuns: ArrayBuffer[ActorRef] = ArrayBuffer.empty[ActorRef]
    var totalRuns: Int = 0
    
    // Testing performance end
    
    def receive: Receive = {
        case AddSpecificNetwork(agents, stopThreshold, iterationLimit, name) =>
            totalRuns += 1
            
            activeRuns += context.actorOf(Props(new Run(
                1000000,
                agents,
                stopThreshold,
                iterationLimit,
                name)), s"$totalRuns")
        
        case AddNetworks(numberOfNetworks, numberOfAgents, density, degreeDistribution, stopThreshold, distribution,
        iterationLimit, agentTypeCount) =>
            totalRuns += 1
            
            activeRuns += context.actorOf(Props(new Run(
                1000000,
                numberOfNetworks,
                numberOfAgents,
                density,
                degreeDistribution,
                stopThreshold,
                distribution,
                iterationLimit,
                agentTypeCount
            )), s"$totalRuns")
            
            activeRuns.last ! StartRun
        
        case RunComplete =>
            println("\nThe run has been complete\n")
    }
    
    def reBalanceUsage(): Unit = {
    
    }
    
    
}
