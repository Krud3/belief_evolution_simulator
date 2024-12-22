import akka.actor.{Actor, ActorRef}
import akka.util.Timeout

import java.util.UUID
import scala.util.Random
import scala.concurrent.duration.*

// DeGroot based Agent base

// Messages
case class SetInitialState(name: String, belief: Float, toleranceRadius: Float, toleranceOffset: Float) // Network -> Agent
case class SetNeighborInfluence(neighbor: ActorRef, influence: Float, biasType: CognitiveBiasType) // Network -> Agent
case object UpdateAgent // Network -> Agent
case object UpdateAgentForce // Network -> Agent
case object SnapShotAgent // Network -> Agent

case class FirstUpdate(neighborSaver: ActorRef, staticSaver: ActorRef) // Network -> Agent

case class AddToNeighborhood(neighbor: ActorRef) // Agent -> self
case class SendBelief(belief: Float) extends AnyVal // Agent -> self
case object Silent // Agent -> self
case class SendInfluenceReduced(belief: Float, influenceRedux: Float) // Agent -> self 

// Data saving messages
case class SendStaticData
(
  id: UUID,
  numberOfNeighbors: Int,
  toleranceRadius: Float,
  tolOffset: Float,
  beliefExpressionThreshold: Option[Float],
  openMindedness: Option[Int],
  causeOfSilence: String,
  effectOfSilence: String,
  beliefUpdateMethod: String,
  name: Option[String] = None
) // Agent -> Agent static data saver

case class AddNeighbor(neighbor: ActorRef, biasType: CognitiveBiasType)

// New Gen Agent
class Agent(id: UUID, silenceStrategy: SilenceStrategy, silenceEffect: SilenceEffect,
            runMetadata: RunMetadata, var name: Option[String] = None) extends Actor {
    
    // 8-byte aligned fields
    private var neighborsRefs: Array[ActorRef] = new Array[ActorRef](16)
    private var neighborsWeights: Array[Float] = new Array[Float](16)
    private var neighborBiases: Array[CognitiveBiasType] = new Array[CognitiveBiasType](16)
    private implicit val timeout: Timeout = Timeout(600.seconds) // 8 bytes
    private var stateData: java.util.HashMap[String, Float] = null
    
    // 4-byte aligned fields (floats)
    private var belief: Float = -1f
    private var beliefChange: Float = 0f
    private var tolRadius: Float = 0.1f
    private var tolOffset: Float = 0f
    private var halfRange = tolRadius + tolOffset
    
    // 4-byte aligned fields (ints)
    private var neighborsSize: Int = 0
    private var timesStable: Int = 0
    private var neighborsReceived: Int = 0
    private var inFavor: Int = 0
    private var against: Int = 0
    private var round: Int = 0
    
    // 1-byte fields
    private var hasUpdatedInfluences: Boolean = false
    private var isSpeaking: Boolean = true
    
    
    def receive: Receive = {
        case AddNeighbor(neighbor, bias) =>
            ensureCapacity()
            addNeighbor(neighbor, biasType = bias)
        
        case SetNeighborInfluence(neighbor, influence, bias) =>
            ensureCapacity()
            addNeighbor(neighbor, influence, bias)
            hasUpdatedInfluences = true
        
        case SetInitialState(name, initialBelief, toleranceRadius, toleranceOffset) =>
                    belief = initialBelief
                    tolRadius = toleranceRadius
                    tolOffset = toleranceOffset
                    halfRange = tolRadius + tolOffset
                    this.name = Some(name)
        
        case FirstUpdate(neighborSaver, agentStaticDataSaver) =>
            if (!hasUpdatedInfluences) generateInfluences()
            if (runMetadata.saveMode.includesAgents) {
                agentStaticDataSaver ! SendStaticData(
                    id, neighborsSize,
                    toleranceRadius = tolRadius, tolOffset = tolOffset,
                    beliefExpressionThreshold = None, openMindedness = None,
                    causeOfSilence = silenceStrategy.toString, effectOfSilence = silenceEffect.toString, beliefUpdateMethod = "DeGroot",
                    name = this.name)
            }
            //println(s"${name.get} $isSpeaking at round $round with belief $belief and belief change $beliefChange")
            if (runMetadata.saveMode.includesNeighbors) 
                neighborSaver ! SendNeighbors(neighborsRefs, neighborsWeights, neighborBiases, neighborsSize)
                
            if (runMetadata.saveMode.includesFirstRound) snapshotAgentState(true)
            
            context.parent ! RunFirstRound
        
        case SendBelief(neighborBelief) =>
            neighborsReceived += 1
            if (isCongruent(neighborBelief)) inFavor += 1
            else against += 1
            beliefChange += CognitiveBiases.applyBias(getBias(sender()), neighborBelief - belief) * 
              getInfluence(sender())
            
            if (neighborsReceived == neighborsSize) {
                round += 1
                isSpeaking = silenceStrategy.determineSilence(inFavor, against)
                belief += beliefChange
                
                if (beliefChange < runMetadata.stopThreshold) timesStable += 1
                else timesStable = 0
                if (runMetadata.saveMode.includesRounds) snapshotAgentState(true)
                context.parent ! AgentUpdated(
                    belief,
                    beliefChange < runMetadata.stopThreshold && timesStable > 1
                    )
                //println(s"${name.get} $isSpeaking at round $round with belief $belief and belief change $beliefChange")
                // Reset
                neighborsReceived = 0
                beliefChange = 0f
                inFavor = 0
                against = 0
            }
        
        case Silent =>
            neighborsReceived += 1
            
            if (neighborsReceived == neighborsSize) {
                round += 1
                isSpeaking = silenceStrategy.determineSilence(inFavor, against)
                belief += beliefChange
                
                if (beliefChange < runMetadata.stopThreshold) timesStable += 1
                else timesStable = 0
                if (runMetadata.saveMode.includesRounds) snapshotAgentState(true)
                context.parent ! AgentUpdated(
                    belief,
                    beliefChange < runMetadata.stopThreshold && timesStable > 1
                )
                
                //println(s"${name.get} $isSpeaking at round $round with belief $belief and belief change $beliefChange")
                
                // Reset
                neighborsReceived = 0
                beliefChange = 0f
                inFavor = 0
                against = 0
            }
            
        case UpdateAgent|UpdateAgentForce =>
            silenceEffect.sendBeliefToNeighbors(neighborsRefs, self, neighborsSize, belief, isSpeaking)
        
        case SnapShotAgent =>
            snapshotAgentState(true)
            
    }
    
    private def snapshotAgentState(forceSnapshot: Boolean = false): Unit = {
        if (beliefChange != 0 || forceSnapshot) {
            
            silenceEffect.getOptionalValues match {
                case Some((name, value)) =>
                    if (stateData == null) {
                        stateData = new java.util.HashMap[String, Float]()
                    }
                    stateData.put(name, value)
                case None =>
            }
            
            silenceStrategy.getOptionalValues match {
                case Some(array) =>
                    if (stateData == null) {
                        stateData = new java.util.HashMap[String, Float](array.length)
                    }
                    var i = 0
                    while (i < array.length) {
                        stateData.put(array(i)._1, array(i)._2)
                        i += 1
                    }
                    
                case None =>
            }
            
            if (isSpeaking) {
                RoundRouter.getRoute ! AgentStateSpeaking(AgentState(
                    id,
                    round,
                    belief,
                    Option(stateData)
                    ))
            } else {
                RoundRouter.getRoute ! AgentStateSilent(AgentState(
                    id,
                    round,
                    belief,
                    Option(stateData)
                    ))
            }
        }
    }
    
    @inline private final def isCongruent(neighborBelief: Float): Boolean = {
        (belief - halfRange) <= neighborBelief && neighborBelief <= (belief + halfRange)
    }
    
    private def ensureCapacity(): Unit = {
        if (neighborsSize >= neighborsRefs.length) {
            val newCapacity = neighborsRefs.length * 2
            neighborsRefs = Array.copyOf(neighborsRefs, newCapacity)
            neighborsWeights = Array.copyOf(neighborsWeights, newCapacity)
            neighborBiases = Array.copyOf(neighborBiases, newCapacity)
        }
    }
    
    private def addNeighbor(neighbor: ActorRef, influence: Float = 0.0f, 
                            biasType: CognitiveBiasType = CognitiveBiasType.DeGroot): Unit = {
        ensureCapacity()
        neighborsRefs(neighborsSize) = neighbor
        neighborsWeights(neighborsSize) = influence
        neighborBiases(neighborsSize) = biasType
        neighborsSize += 1
    }
    
    private def getInfluence(neighbor: ActorRef): Float = {
        var i = 0
        while (i < neighborsSize) {
            if (neighborsRefs(i) == neighbor) return neighborsWeights(i)
            i += 1
        }
        0f
    }
    
    private def getBias(neighbor: ActorRef): CognitiveBiasType = {
        var i = 0
        while (i < neighborsSize) {
            if (neighborsRefs(i) == neighbor) return neighborBiases(i)
            i += 1
        }
        CognitiveBiasType.DeGroot
    }
    
    private def generateInfluences(): Unit = {
        val random = new Random
        val totalSize = neighborsSize + 1
        
        val randomNumbers = Array.fill(totalSize)(random.nextFloat())
        val sum = randomNumbers.sum 
        
        var i = 0
        while (i < neighborsSize) {
            neighborsWeights(i) = randomNumbers(i) / sum
            i += 1
        }
        
        hasUpdatedInfluences = true
    }
    
    override def preStart(): Unit = {
        runMetadata.distribution match {
            case Uniform =>
                belief = randomBetweenF()
            
            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution
            
            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
            
            case BiModal(peak1, peak2, lower, upper) =>
            
            case _ =>
            
        }
    }
}

// Actor
//trait AgentStateSnapshot {
//    protected def snapshotAgentState(forceSnapshot: Boolean): Unit
//}
//
//abstract class DeGrootianAgent extends Actor with AgentStateSnapshot {
//    // Constructor parameters
//    protected val network: ActorRef = context.parent
//
//    // 8-byte aligned fields
//    protected var neighborsRefs: Array[ActorRef] = new Array[ActorRef](16)
//    protected var neighborsWeights: Array[Float] = new Array[Float](16)
//    protected var name: String = ""
//    implicit val timeout: Timeout = Timeout(600.seconds) // 8 bytes
//
//    // 4-byte aligned fields (floats)
//    protected var belief: Float = -1f
//    protected var beliefChange: Float = 0f
//    protected var tolRadius: Float = 0.1f
//    protected val tolOffset: Float = 0f
//    private var halfRange = tolRadius + tolOffset
//
//    // 4-byte aligned fields (ints)
//    protected var neighborsSize: Int = 0
//    protected var timesStable: Int = 0
//    protected var neighborsReceived: Int = 0
//    protected var inFavor: Int = 0
//    protected var against: Int = 0
//    protected var round: Int = 0
//
//    // 1-byte field
//    protected var hasUpdatedInfluences: Boolean = false
//
//    @inline protected final def isCongruent(neighborBelief: Float): Boolean = {
//        (belief - halfRange) <= neighborBelief  && neighborBelief <= (belief + halfRange)
//    }
//
//    protected def updateRound(): Unit = {
//        round += 1
//    }
//
//    // Currently just places random numbers as the influences
//    private def generateInfluences(): Unit = {
//        val random = new Random
//        val totalSize = neighborsSize + 1
//
//        val randomNumbers = Array.fill(totalSize)(random.nextFloat())
//        val sum = randomNumbers.sum
//
//        System.arraycopy(randomNumbers, 0, neighborsWeights, 0, neighborsSize)
//        var i = 0
//        while (i < neighborsSize) {
//            neighborsWeights(i) /= sum
//            i += 1
//        }
//
//        hasUpdatedInfluences = true
//    }
//
//    private def ensureCapacity(): Unit = {
//        if (neighborsSize >= neighborsRefs.length) {
//            val newCapacity = neighborsRefs.length * 2
//            neighborsRefs = Array.copyOf(neighborsRefs, newCapacity)
//            neighborsWeights = Array.copyOf(neighborsWeights, newCapacity)
//        }
//    }
//
//    private def addNeighbor(neighbor: ActorRef, influence: Float): Unit = {
//        ensureCapacity()
//        neighborsRefs(neighborsSize) = neighbor
//        neighborsWeights(neighborsSize) = influence
//        neighborsSize += 1
//    }
//
//    protected def getInfluence(neighbor: ActorRef): Float = {
//        var i = 0
//        while (i < neighborsSize) {
//            if (neighborsRefs(i) == neighbor) return neighborsWeights(i)
//            i += 1
//        }
//        0f
//    }
//
//    protected def sendStaticData(): Unit = {}
//
//    def receive: Receive = {
//        case AddToNeighborhood(neighbor) =>
//            addNeighbor(neighbor, 0f)
//
////        case SetNeighborInfluence(neighbor, influence) =>
////            addNeighbor(neighbor, influence)
////            hasUpdatedInfluences = true
//
//        case SetInitialState(initialBelief, toleranceRadius, name) =>
//            belief = initialBelief
//            tolRadius = toleranceRadius
//            halfRange = tolRadius + tolOffset
//            this.name = name
//
////        case FirstUpdate(neighborSaver, staticSaver) =>
////            if (!hasUpdatedInfluences) generateInfluences()
////            neighborSaver !  SendNeighbors(neighborsRefs, neighborsWeights, neighborsSize)
////            snapshotAgentState(true)
//
//        case SnapShotAgent =>
//            // snapshotAgentState(true)
//            context.stop(self)
//    }
//}