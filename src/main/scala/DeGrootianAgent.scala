import akka.actor.{Actor, ActorRef}
import akka.util.Timeout

import scala.util.Random
import scala.concurrent.duration.*

// DeGroot based Agent base

// Messages
case class SetInitialState(belief: Float, toleranceRadius: Float, name: String) // Network -> Agent
case class SetNeighborInfluence(neighbor: ActorRef, influence: Float) // Network -> Agent
case object UpdateAgent // Network -> Agent
case object UpdateAgentForce // Network -> Agent

case object SaveAgentStaticData // Network -> Agent
case object SnapShotAgent // Network -> Agent

case class AddToNeighborhood(neighbor: ActorRef) // Agent -> self
case class SendBelief(belief: Float) extends AnyVal // Agent -> self
case object Silent // Agent -> self

// Data saving messages
case class SendNeighbors(refs: Array[ActorRef], weights: Array[Float], size: Int) // Agent -> NetworkSaver
case class SendStaticData(staticData: StaticAgentData) // Agent ->

// Actor
trait AgentStateSnapshot {
    protected def snapshotAgentState(forceSnapshot: Boolean): Unit
}

abstract class DeGrootianAgent extends Actor with AgentStateSnapshot {
    // Constructor parameters
    protected def networkSaver: ActorRef
    protected val network: ActorRef = context.parent
    
    // 8-byte aligned fields
    protected var neighborsRefs: Array[ActorRef] = new Array[ActorRef](16)
    protected var neighborsWeights: Array[Float] = new Array[Float](16)
    protected var name: String = ""
    implicit val timeout: Timeout = Timeout(600.seconds) // 8 bytes
    
    // 4-byte aligned fields (floats)
    protected var belief: Float = -1f
    protected var beliefChange: Float = 0f
    protected var tolRadius: Float = 0.1f
    protected val tolOffset: Float = 0f
    private var halfRange = tolRadius + tolOffset
    protected var selfInfluence: Float = 1f
    
    // 4-byte aligned fields (ints)
    protected var neighborsSize: Int = 0
    protected var timesStable: Int = 0
    protected var neighborsReceived: Int = 0
    protected var inFavor: Int = 0
    protected var against: Int = 0
    protected var round: Int = 0
    
    // 1-byte field
    protected var hasUpdatedInfluences: Boolean = false
    
    @inline protected final def isCongruent(neighborBelief: Float): Boolean = {
        (belief - halfRange) <= neighborBelief  && neighborBelief <= (belief + halfRange)
    }
    
    protected def updateRound(): Unit = {
        if (round == 0) {
            if (!hasUpdatedInfluences) generateInfluences()
            // Save Network structure
            // networkSaver !  SendNeighbors(neighborsRefs, neighborsWeights, neighborsSize)
            // Save first round state
            snapshotAgentState(true)
        }
        round += 1
    }
    
    // Currently just places random numbers as the influences
    private def generateInfluences(): Unit = {
        val random = new Random
        val totalSize = neighborsSize + 1
        
        // Generate all random numbers first
        val randomNumbers = Array.fill(totalSize)(random.nextFloat())
        val sum = randomNumbers.sum // Built-in sum is fine for small arrays
        
        // Update neighbor weights
        System.arraycopy(randomNumbers, 0, neighborsWeights, 0, neighborsSize)
        var i = 0
        while (i < neighborsSize) {
            neighborsWeights(i) /= sum
            i += 1
        }
        
        // Set self influence
        selfInfluence = randomNumbers(neighborsSize) / sum
        hasUpdatedInfluences = true
    }
    
    private def ensureCapacity(): Unit = {
        if (neighborsSize >= neighborsRefs.length) {
            val newCapacity = neighborsRefs.length * 2
            neighborsRefs = Array.copyOf(neighborsRefs, newCapacity)
            neighborsWeights = Array.copyOf(neighborsWeights, newCapacity)
        }
    }
    
    private def addNeighbor(neighbor: ActorRef, influence: Float): Unit = {
        ensureCapacity()
        neighborsRefs(neighborsSize) = neighbor
        neighborsWeights(neighborsSize) = influence
        neighborsSize += 1
    }
    
    protected def getInfluence(neighbor: ActorRef): Float = {
        var i = 0
        while (i < neighborsSize) {
            if (neighborsRefs(i) == neighbor) return neighborsWeights(i)
            i += 1
        }
        0f
    }
    
    def receive: Receive = {
        case AddToNeighborhood(neighbor) =>
            addNeighbor(neighbor, 0f)
        
        case SetNeighborInfluence(neighbor, influence) =>
            addNeighbor(neighbor, influence)
            selfInfluence -= influence
            hasUpdatedInfluences = true
        
        case SetInitialState(initialBelief, toleranceRadius, name) =>
            belief = initialBelief
            tolRadius = toleranceRadius
            halfRange = tolRadius + tolOffset
            this.name = name
        
        case SnapShotAgent =>
            snapshotAgentState(true)
            context.stop(self)
    }
}