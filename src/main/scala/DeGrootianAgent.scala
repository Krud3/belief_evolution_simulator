import akka.actor.{Actor, ActorRef, Stash}
import akka.dispatch.ControlMessage
import akka.util.Timeout
import akka.pattern.ask

import scala.util.Random
import scala.concurrent.duration.*
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

// DeGroot based Agent base

// Messages
case class SetInitialState(belief: Float, toleranceRadius: Float, name: String) // Network -> Agent

case class AddToNeighborhood(neighbor: ActorRef) // Agent -> self

case class RequestBelief(roundSentFrom: Int) // self -> Agent
case class SendBelief(belief: Float, senderAgent: ActorRef) // Agent -> self

case class AgentUpdated(hasNextIter: Boolean, belief: Float, isStable: Boolean, 
                        updatedBelief: Boolean) // Agent -> network

// Data saving messages
case class SendNeighbors(neighbors: mutable.Map[ActorRef, Float]) // Agent -> NetworkSaver
case class SendStaticData(staticData: StaticAgentData) // Agent -> 

// Actor
class DeGrootianAgent extends Actor with Stash {
    //
    var name: String = ""
    
    // Parent
    val network: ActorRef = context.parent
    
    // Belief related
    var belief: Float = -1f
    var prevBelief: Float = -1f
    var tolRadius: Float = 0.1 //randomBetweenF(0f, 1f)
    val tolOffset: Float = 0 // randomBetweenF(-tolRadius, tolRadius)
    
    // Neighbors and influence
    var neighbors: mutable.Map[ActorRef, Float] = mutable.Map()
    var selfInfluence: Float = 1
    var hasUpdatedInfluences: Boolean = false
    
    // Round data
    var round: Int = 0
    
    // Ask patter timeout ToDo implement better strategy for timeout
    implicit val timeout: Timeout = Timeout(600.seconds)
    
    
    protected def isCongruent(neighborBelief: Double): Boolean = {
        val lower = prevBelief - tolRadius + tolOffset
        val upper = prevBelief + tolRadius + tolOffset
        lower <= neighborBelief && neighborBelief <= upper
    }
    
    // Currently just places random numbers as the influences
    protected def generateInfluences(): Unit = {
        val random = new Random
        val randomNumbers = Vector.fill(neighbors.size + 1)(random.nextFloat())
        val sum = randomNumbers.sum
        val influences = randomNumbers.map(_ / sum)
        neighbors.keys.zip(influences).foreach { case (actorRef, influence) =>
            neighbors(actorRef) = influence
        }
        selfInfluence = influences.last
        hasUpdatedInfluences = true
    }
    
    protected def fetchBeliefsFromNeighbors(callback: Seq[SendBelief] => Unit): Unit = {
        val futures = neighbors.keys.map { neighbor =>
            (neighbor ? RequestBelief(round)).mapTo[SendBelief]
        }
        
        val aggregatedFutures = Future.sequence(futures).map(_.toSeq)
        
        aggregatedFutures.onComplete {
            case Success(beliefs) =>
                callback(beliefs)
            
            case Failure(exception) =>
                println(s"Error retrieving beliefs from neighbors: $exception")
        }
    }

    
    def receive: Receive = {
        case AddToNeighborhood(neighbor) =>
            neighbors.put(neighbor, 0f)
        
        case SetNeighborInfluence(neighbor, influence) =>
            neighbors.put(neighbor, influence)
            selfInfluence -= influence
            hasUpdatedInfluences = true
        
        case SetInitialState(initialBelief, toleranceRadius, name) =>
            belief = initialBelief
            prevBelief = belief
            tolRadius = toleranceRadius
            this.name = name
        
        case RequestBelief(roundSentFrom) if roundSentFrom != round =>
            stash()
            
    }
    
    
}
