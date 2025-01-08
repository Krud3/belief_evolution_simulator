import akka.actor.{Actor, ActorRef}
import akka.util.Timeout

import java.util.UUID
import scala.util.Random
import scala.concurrent.duration.*

// DeGroot based Agent base

// Messages
case class SetInitialState(name: String, belief: Float, toleranceRadius: Float, toleranceOffset: Float) // Network -> Agent
case class SetNeighborInfluence(neighbor: Int, influence: Float, biasType: CognitiveBiasType) // Network -> Agent
case object UpdateAgent1R // Network -> Agent
case object UpdateAgent2R // Network -> Agent
case object SnapShotAgent // Network -> Agent
case class FirstUpdate(neighborSaver: ActorRef, staticSaver: ActorRef, agents: Array[ActorRef]) // Network -> Agent

case class SendBelief(belief: Float) extends AnyVal // Agent -> self
case object Silent // Agent -> self

case class AddNeighbor(neighbor: Int, biasType: CognitiveBiasType) // Agent -> self
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

// New Gen Agent
class Agent(id: UUID, silenceStrategy: SilenceStrategy, silenceEffect: SilenceEffect,
    runMetadata: RunMetadata, beliefBuffer1: Array[Float], beliefBuffer2: Array[Float],
    speakingBuffer1: AgentStates, speakingBuffer2: AgentStates, agentIndex: Int,
    var name: Option[String] = None) extends Actor {
    // 8-byte aligned fields
    private var neighborsRefs: Array[Int] = new Array[Int](8)
    private var neighborsWeights: Array[Float] = new Array[Float](8)
    private var neighborBiases: Array[CognitiveBiasType] = new Array[CognitiveBiasType](8)
    private implicit val timeout: Timeout = Timeout(600.seconds) // 8 bytes
    private var stateData: java.util.HashMap[String, Float] = null
    
    // 4-byte aligned fields (floats)
    private var belief: Float = -1f
    private var tolRadius: Float = 0.1f
    private var tolOffset: Float = 0f
    private var halfRange = tolRadius + tolOffset
    
    // 4-byte aligned fields (ints)
    private var neighborsSize: Int = 0
    private var timesStable: Int = 0
    private var inFavor: Int = 0
    private var against: Int = 0
    private var round: Int = 0
    
    // 1-byte fields
    private val hasMemory: Boolean = silenceEffect.toString == "Memory"
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
        
        case FirstUpdate(neighborSaver, agentStaticDataSaver, agents) =>
            beliefBuffer1(agentIndex) = silenceEffect.getPublicValue(belief, isSpeaking)
            if (!hasUpdatedInfluences) generateInfluences()
            if (runMetadata.saveMode.includesAgents) {
                agentStaticDataSaver ! SendStaticData(
                    id, neighborsSize,
                    toleranceRadius = tolRadius, tolOffset = tolOffset,
                    beliefExpressionThreshold = None, openMindedness = None,
                    causeOfSilence = silenceStrategy.toString, effectOfSilence = silenceEffect.toString, beliefUpdateMethod = "DeGroot",
                    name = name)
            }
            if (runMetadata.saveMode.includesNeighbors) {
                val neighborActors: Array[ActorRef] = Array.ofDim[ActorRef](neighborsSize)
                var i = 0
                while (i < neighborsSize - 3) {
                    neighborActors(i) = agents(neighborsRefs(i))
                    neighborActors(i + 1) = agents(neighborsRefs(i + 1))
                    neighborActors(i + 2) = agents(neighborsRefs(i + 2))
                    neighborActors(i + 3) = agents(neighborsRefs(i + 3))
                    i += 4
                }
                while (i < neighborsSize) {
                    neighborActors(i) = agents(neighborsRefs(i))
                    i += 1
                }
                neighborSaver ! SendNeighbors(neighborActors, neighborsWeights, neighborBiases, neighborsSize)
            }
            if (runMetadata.saveMode.includesFirstRound) snapshotAgentState()
            
            context.parent ! RunFirstRound
            
        case UpdateAgent1R =>
            // Update belief 1 = read buffers, 2 = write buffers
            var i = 0
            val initialBelief = belief
            val sumValues = new Array[Float](4)
            while (i < neighborsSize - 3) {
                // Cache belief buffer values
                val b0 = beliefBuffer1(neighborsRefs(i))
                val b1 = beliefBuffer1(neighborsRefs(i + 1))
                val b2 = beliefBuffer1(neighborsRefs(i + 2))
                val b3 = beliefBuffer1(neighborsRefs(i + 3))
                
                // Sum calculations S * Bias(Bj - Bi) * I_j
                sumValues(0) += speakingBuffer1.getStateAsFloatWithMemory(neighborsRefs(i), hasMemory) *
                  CognitiveBiases.applyBias(neighborBiases(i), b0 - initialBelief) * neighborsWeights(i)
                sumValues(1) += speakingBuffer1.getStateAsFloatWithMemory(neighborsRefs(i + 1), hasMemory) *
                  CognitiveBiases.applyBias(neighborBiases(i + 1), b1 - initialBelief) * neighborsWeights(i + 1)
                sumValues(2) += speakingBuffer1.getStateAsFloatWithMemory(neighborsRefs(i + 2), hasMemory) *
                  CognitiveBiases.applyBias(neighborBiases(i + 2), b2 - initialBelief) * neighborsWeights(i + 2)
                sumValues(3) += speakingBuffer1.getStateAsFloatWithMemory(neighborsRefs(i + 3), hasMemory) *
                  CognitiveBiases.applyBias(neighborBiases(i + 3), b3 - initialBelief) * neighborsWeights(i + 3)
                
                if (speakingBuffer1.getStateAsBoolean(neighborsRefs(i)) || hasMemory) congruent(b0)
                if (speakingBuffer1.getStateAsBoolean(neighborsRefs(i + 1)) || hasMemory) congruent(b1)
                if (speakingBuffer1.getStateAsBoolean(neighborsRefs(i + 2)) || hasMemory) congruent(b2)
                if (speakingBuffer1.getStateAsBoolean(neighborsRefs(i + 3)) || hasMemory) congruent(b3)
                
                i += 4
            }
            
            while (i < neighborsSize) {
                belief += speakingBuffer1.getStateAsFloatWithMemory(neighborsRefs(i), hasMemory) * CognitiveBiases.applyBias(neighborBiases(i), beliefBuffer1(neighborsRefs(i)) - initialBelief) * neighborsWeights(i)
                if (speakingBuffer1.getStateAsBoolean(neighborsRefs(i)) || hasMemory) congruent(beliefBuffer1(neighborsRefs(i)))
                i += 1
            }
            
            belief += sumValues(0) + sumValues(1) + sumValues(2) + sumValues(3)
            
            round += 1
            isSpeaking = silenceStrategy.determineSilence(inFavor, against)
            
            // Write to buffers
            speakingBuffer2.setState(agentIndex, isSpeaking)
            beliefBuffer2(agentIndex) = silenceEffect.getPublicValue(belief, isSpeaking)
            // println(s"Round $round At ${name.get} $agentIndex with ${silenceEffect.getPublicValue(belief, isSpeaking)} at ${writeBuffer(agentIndex)} and $isSpeaking")
            val isStable = math.abs(belief - beliefBuffer1(agentIndex)) < runMetadata.stopThreshold
            if (isStable) timesStable += 1
            else timesStable = 0
            if (runMetadata.saveMode.includesRounds && math.abs(belief - beliefBuffer1(agentIndex)) != 0) snapshotAgentState()
            
            inFavor = 0
            against = 0
            context.parent ! AgentUpdated(belief, isStable && timesStable > 1)
        
        case UpdateAgent2R =>
            // Update belief 2 = read buffers, 1 = write buffers
            var i = 0
            val initialBelief = belief
            val sumValues = new Array[Float](4)
            while (i < neighborsSize - 3) {
                sumValues(0) += speakingBuffer2.getStateAsFloatWithMemory(neighborsRefs(i), hasMemory) * CognitiveBiases.applyBias(neighborBiases(i), beliefBuffer2(neighborsRefs(i)) - initialBelief) * neighborsWeights(i)
                sumValues(1) += speakingBuffer2.getStateAsFloatWithMemory(neighborsRefs(i + 1), hasMemory) * CognitiveBiases.applyBias(neighborBiases(i + 1), beliefBuffer2(neighborsRefs(i + 1)) - initialBelief) * neighborsWeights(i + 1)
                sumValues(2) += speakingBuffer2.getStateAsFloatWithMemory(neighborsRefs(i + 2), hasMemory) * CognitiveBiases.applyBias(neighborBiases(i + 2), beliefBuffer2(neighborsRefs(i + 2)) - initialBelief) * neighborsWeights(i + 2)
                sumValues(3) += speakingBuffer2.getStateAsFloatWithMemory(neighborsRefs(i + 3), hasMemory) * CognitiveBiases.applyBias(neighborBiases(i + 3), beliefBuffer2(neighborsRefs(i + 3)) - initialBelief) * neighborsWeights(i + 3)
                
                if (speakingBuffer2.getStateAsBoolean(neighborsRefs(i)) || hasMemory) congruent(beliefBuffer2(neighborsRefs(i)))
                if (speakingBuffer2.getStateAsBoolean(neighborsRefs(i + 1)) || hasMemory) congruent(beliefBuffer2(neighborsRefs(i + 1)))
                if (speakingBuffer2.getStateAsBoolean(neighborsRefs(i + 2)) || hasMemory) congruent(beliefBuffer2(neighborsRefs(i + 2)))
                if (speakingBuffer2.getStateAsBoolean(neighborsRefs(i + 3)) || hasMemory) congruent(beliefBuffer2(neighborsRefs(i + 3)))
                i += 4
            }
            
            while (i < neighborsSize) {
                belief += speakingBuffer2.getStateAsFloatWithMemory(neighborsRefs(i), hasMemory) * CognitiveBiases.applyBias(neighborBiases(i), beliefBuffer2(neighborsRefs(i)) - initialBelief) * neighborsWeights(i)
                if (speakingBuffer2.getStateAsBoolean(neighborsRefs(i)) || hasMemory) congruent(beliefBuffer2(neighborsRefs(i)))
                i += 1
            }
            
            belief += sumValues(0) + sumValues(1) + sumValues(2) + sumValues(3)
            
            round += 1
            isSpeaking = silenceStrategy.determineSilence(inFavor, against)
            
            // Write to buffers
            speakingBuffer1.setState(agentIndex, isSpeaking)
            beliefBuffer1(agentIndex) = silenceEffect.getPublicValue(belief, isSpeaking)
            // println(s"Round $round At ${name.get} $agentIndex with ${silenceEffect.getPublicValue(belief, isSpeaking)} at ${writeBuffer(agentIndex)} and $isSpeaking")
            val isStable = math.abs(belief - beliefBuffer2(agentIndex)) < runMetadata.stopThreshold
            if (isStable) timesStable += 1
            else timesStable = 0
            if (runMetadata.saveMode.includesRounds && math.abs(belief - beliefBuffer2(agentIndex)) != 0) snapshotAgentState()
            
            inFavor = 0
            against = 0
            context.parent ! AgentUpdated(belief, isStable && timesStable > 1)
            
        case SnapShotAgent =>
            snapshotAgentState()
            
    }
    
    private def snapshotAgentState(): Unit = {
        silenceEffect.getOptionalValues match {
            case Some((name, value)) =>
                if (stateData == null) stateData = new java.util.HashMap[String, Float]()
                stateData.put(name, value)
            case None =>
        }
        
        silenceStrategy.getOptionalValues match {
            case Some(array) =>
                if (stateData == null) stateData = new java.util.HashMap[String, Float](array.length)
                var i = 0
                while (i < array.length) {
                    stateData.put(array(i)._1, array(i)._2)
                    i += 1
                }
            
            case None =>
        }
        val agentState = AgentState(id, round, belief, Option(stateData))
        if (isSpeaking) RoundRouter.getRoute ! AgentStateSpeaking(agentState)
        else RoundRouter.getRoute ! AgentStateSilent(agentState)
    }
    
    inline private final def congruent(neighborBelief: Float): Unit = {
        if ((belief - halfRange) <= neighborBelief && neighborBelief <= (belief + halfRange)) inFavor += 1
        else against += 1
    }
    
    inline private def boolToFloat(b: Boolean): Float = if b || hasMemory then 1.0f else 0.0f
    
    private def ensureCapacity(): Unit = {
        if (neighborsSize >= neighborsRefs.length) {
            val newCapacity = neighborsRefs.length * 2
            neighborsRefs = Array.copyOf(neighborsRefs, newCapacity)
            neighborsWeights = Array.copyOf(neighborsWeights, newCapacity)
            neighborBiases = Array.copyOf(neighborBiases, newCapacity)
        }
    }
    
    private def addNeighbor(neighbor: Int, influence: Float = 0.0f,
                            biasType: CognitiveBiasType = CognitiveBiasType.DeGroot): Unit = {
        ensureCapacity()
        neighborsRefs(neighborsSize) = neighbor
        neighborsWeights(neighborsSize) = influence
        neighborBiases(neighborsSize) = biasType
        neighborsSize += 1
    }
    
    private def getInfluence(neighborRef: Int): Float = {
        var i = 0
        while (i < neighborsSize) {
            if (neighborsRefs(i) == neighborRef) return neighborsWeights(i)
            i += 1
        }
        0f
    }
    
    private def getInfluences(startIndex: Int): Array[Float] = {
        val influences: Array[Float] = Array.ofDim[Float](4)
        influences(0) = neighborsWeights(startIndex)
        influences(1) = neighborsWeights(startIndex + 1)
        influences(2) = neighborsWeights(startIndex + 2)
        influences(3) = neighborsWeights(startIndex + 3)
        
        influences
    }
    
    private def getBias(neighbor: Int): CognitiveBiasType = {
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