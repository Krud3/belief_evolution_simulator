import akka.actor.{Actor, ActorRef}
import akka.util.Timeout

import odbf.Encoder

import java.util.UUID
import scala.util.Random
import scala.concurrent.duration.*
import scala.collection.mutable.ArrayBuffer

import language.future

// DeGroot based Agent base

// Messages
case class SetInitialState(
    belief: Float, 
    toleranceRadius: Float, 
    toleranceOffset: Float, 
    agentIndex: Int
) // Network -> Agent

case object AddNames // Network -> Agent
case class SetNeighborInfluence(agent: Int, neighbor: Int, influence: Float, biasType: CognitiveBiasType) // Network -> Agent
case object UpdateAgent1R // Network -> Agent
case object UpdateAgent2R // Network -> Agent
case object SnapShotAgent // Network -> Agent
case class FirstUpdate(neighborSaver: ActorRef, staticSaver: ActorRef, agents: Array[ActorRef]) // Network -> Agent

case class SendBelief(belief: Float) extends AnyVal // Agent -> self
case class SendInfluenceReduced(belief: Float, influenceRedux: Float) // Agent -> self
case object Silent // Agent -> self

case class AddNeighbor(agent: Int, neighbor: Int, biasType: CognitiveBiasType) // Network -> self
case class AddNeighbors(agent: Int, neighbors: Array[Int], biasType: CognitiveBiasType) // Network -> agent

// Data saving messages
case class StaticAgentData(
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
) // Agent -> Agent static data saver SendStaticData

// Estimate size
// 16 + 8 + 8 + (4 * 2) + 0.25 + 4 + (4 * m * 2) * 3 + 32 + (4 * 4) + (4 * 4) + 2

// ToDos
// Optimize silence array and silence strategy to use ranges

// New Gen Agent
class Agent(ids: Array[UUID], silenceStrategy: Array[SilenceStrategy], silenceEffect: Array[SilenceEffect],
    runMetadata: RunMetadata, beliefBuffer1: Array[Float], beliefBuffer2: Array[Float],
    speakingBuffer1: AgentStates, speakingBuffer2: AgentStates, numberOfAgents: Int, startsAt: Int,
    names:Array[String])
  extends Actor {
    
    // 8-byte aligned fields
    var neighborsRefs: Array[Array[Int]] = Array.ofDim[Int](numberOfAgents, 16)
    var neighborsWeights: Array[Array[Float]] = Array.ofDim[Float](numberOfAgents, 16)
    var neighborBiases: Array[Array[CognitiveBiasType]] = Array.ofDim[CognitiveBiasType](numberOfAgents, 16)
    var stateData = Array.ofDim[java.util.HashMap[String, Float]](numberOfAgents)
    
    
    // 4-byte aligned fields (floats)
    var belief: Array[Float] = Array.fill(numberOfAgents)(-1f)
    var tolRadius: Array[Float] = Array.fill(numberOfAgents)(0.1f)
    var tolOffset: Array[Float] = Array.fill(numberOfAgents)(0f)
    var halfRange: Array[Float] = Array.fill(numberOfAgents)(tolRadius(0) + tolOffset(0))
    
    // 4-byte aligned fields (ints)
    var neighborsSize: Array[Int] = new Array[Int](numberOfAgents)
    var timesStable: Array[Int] = new Array[Int](numberOfAgents)
    var inFavor: Array[Int] = new Array[Int](numberOfAgents)
    var against: Array[Int] = new Array[Int](numberOfAgents)
    
    // 1-byte fields
    var isSpeaking: Array[Boolean] = Array.fill(numberOfAgents)(true)
    var hasMemory: Array[Boolean] = new Array[Boolean](numberOfAgents)
    var i = 0
    while (i < numberOfAgents) {
        hasMemory(i) = silenceEffect(i).isInstanceOf[MemoryEffect]
        i += 1
    }
    
    // Scalar fields
    val encoder: Encoder = Encoder()
    implicit val timeout: Timeout = Timeout(600.seconds) // 8 bytes
    var round: Int = 0
    var hasUpdatedInfluences: Boolean = false
    var isGenerated: Boolean = true
    
    def receive: Receive = {
        case AddNeighbor(agent, neighbor, bias) =>
            addNeighbor(agent - startsAt, neighbor, biasType = bias)
        
        case SetNeighborInfluence(agent, neighbor, influence, bias) =>
            addNeighbor(agent, neighbor, influence, bias)
            hasUpdatedInfluences = true
            isGenerated = false
        
        case SetInitialState(initialBelief, toleranceRadius, toleranceOffset, agentIndex) =>
            belief(agentIndex) = initialBelief
            tolRadius(agentIndex) = toleranceRadius
            tolOffset(agentIndex) = toleranceOffset
            halfRange(agentIndex) = toleranceRadius + toleranceOffset
        
        case FirstUpdate(neighborSaver, agentStaticDataSaver, agents) =>
            val neighborActors = new ArrayBuffer[NeighborStructure](numberOfAgents * 2)
            val agentsStaticStates = new Array[StaticAgentData](numberOfAgents)
            var i = 0
            while (i < numberOfAgents) {
                beliefBuffer1(i + startsAt) = belief(i)
                silenceEffect(i) match { case m: MemoryEffect => m.initialize(belief(i)) case _ => }
                if (!hasUpdatedInfluences) generateInfluences()
                if (runMetadata.saveMode.includesAgents) {
                    agentsStaticStates(i) = StaticAgentData(
                        ids(i + startsAt),
                        neighborsSize(i),
                        toleranceRadius = tolRadius(i),
                        tolOffset = tolOffset(i),
                        beliefExpressionThreshold = None,
                        openMindedness = None,
                        causeOfSilence = silenceStrategy(i).toString,
                        effectOfSilence = silenceEffect(i).toString,
                        beliefUpdateMethod = "DeGroot",
                        name = if (isGenerated) None else Option(names(i))
                        )
                }
                
                if (runMetadata.saveMode.includesNeighbors) {
                    var j = 0
                    while (j < neighborsSize(i)) {
                        neighborActors.addOne(NeighborStructure(
                            ids(i + startsAt),
                            ids(neighborsRefs(i)(j)),
                            neighborsWeights(i)(j),
                            neighborBiases(i)(j)
                            ))
                        j += 1
                    }
                }
                i += 1
            }
            if (runMetadata.saveMode.includesFirstRound) snapshotAgentState(true, null)
            if (runMetadata.saveMode.includesNeighbors) neighborSaver ! SendNeighbors(neighborActors.view)
            if (runMetadata.saveMode.includesAgents) agentStaticDataSaver ! SendStaticAgentData(agentsStaticStates.view)
            context.parent ! RunFirstRound
            
        case UpdateAgent1R =>
            // Update belief 1 = read buffers, 2 = write buffers
            updateBuffers(beliefBuffer1, beliefBuffer2, speakingBuffer1, speakingBuffer2)
        
        case UpdateAgent2R =>
            // Update belief 2 = read buffers, 1 = write buffers
            updateBuffers(beliefBuffer2, beliefBuffer1, speakingBuffer2, speakingBuffer1)
            
        case SnapShotAgent =>
            snapshotAgentState(true, null)
            
    }
    
    def updateBuffers(
        readBeliefBuffer: Array[Float],
        writeBeliefBuffer: Array[Float],
        readSpeakingBuffer: AgentStates,
        writeSpeakingBuffer: AgentStates
    ): Unit = {
        var maxBelief = -1f
        var minBelief = 2f
        var existsStableAgent = true
        var i = 0
        while (i < numberOfAgents) {
            var j = 0
            val initialBelief = belief(i)
            val sumValues = new Array[Float](4)
            while (j < neighborsSize(i) - 3) {
                // Cache belief buffer values
                val b0 = readBeliefBuffer(neighborsRefs(i)(j))
                val b1 = readBeliefBuffer(neighborsRefs(i)(j + 1))
                val b2 = readBeliefBuffer(neighborsRefs(i)(j + 2))
                val b3 = readBeliefBuffer(neighborsRefs(i)(j + 3))
                
                // Sum calculations S * Bias(Bj - Bi) * I_j
                sumValues(0) += readSpeakingBuffer.getStateAsFloatWithMemory(neighborsRefs(i)(j), hasMemory(i)) *
                  CognitiveBiases.applyBias(neighborBiases(i)(j), b0 - initialBelief) * neighborsWeights(i)(j)
                sumValues(1) += readSpeakingBuffer.getStateAsFloatWithMemory(neighborsRefs(i)(j + 1), hasMemory(i)) *
                  CognitiveBiases.applyBias(neighborBiases(i)(j + 1), b1 - initialBelief) * neighborsWeights(i)(j + 1)
                sumValues(2) += readSpeakingBuffer.getStateAsFloatWithMemory(neighborsRefs(i)(j + 2), hasMemory(i)) *
                  CognitiveBiases.applyBias(neighborBiases(i)(j + 2), b2 - initialBelief) * neighborsWeights(i)(j + 2)
                sumValues(3) += readSpeakingBuffer.getStateAsFloatWithMemory(neighborsRefs(i)(j + 3), hasMemory(i)) *
                  CognitiveBiases.applyBias(neighborBiases(i)(j + 3), b3 - initialBelief) * neighborsWeights(i)(j + 3)
                
                if (readSpeakingBuffer.getStateAsBoolean(neighborsRefs(i)(j)) || hasMemory(i)) congruent(b0, i)
                if (readSpeakingBuffer.getStateAsBoolean(neighborsRefs(i)(j + 1)) || hasMemory(i)) congruent(b1, i)
                if (readSpeakingBuffer.getStateAsBoolean(neighborsRefs(i)(j + 2)) || hasMemory(i)) congruent(b2, i)
                if (readSpeakingBuffer.getStateAsBoolean(neighborsRefs(i)(j + 3)) || hasMemory(i)) congruent(b3, i)
                
                j += 4
            }
            
            while (j < neighborsSize(i)) {
                belief(i) += readSpeakingBuffer.getStateAsFloatWithMemory(neighborsRefs(i)(j), hasMemory(i)) *
                  CognitiveBiases.applyBias(neighborBiases(i)(j), readBeliefBuffer(neighborsRefs(i)(j)) - initialBelief) *
                  neighborsWeights(i)(j)
                if (readSpeakingBuffer.getStateAsBoolean(neighborsRefs(i)(j)) || hasMemory(i))
                    congruent(readBeliefBuffer(neighborsRefs(i)(j)), i)
                j += 1
            }
            belief(i) += sumValues(0) + sumValues(1) + sumValues(2) + sumValues(3)
            isSpeaking(i) = silenceStrategy(i).determineSilence(inFavor(i), against(i))
            
            // Write to buffers
            val agentIndex = i + startsAt
            writeSpeakingBuffer.setState(agentIndex, isSpeaking(i))
            writeBeliefBuffer(agentIndex) = silenceEffect(i).getPublicValue(belief(i), isSpeaking(i))
            val isStable = math.abs(belief(i) - initialBelief) < runMetadata.stopThreshold
            if (isStable) timesStable(i) += 1
            else timesStable(i) = 0
            
            inFavor(i) = 0
            against(i) = 0
            maxBelief = math.max(maxBelief, writeBeliefBuffer(agentIndex))
            minBelief = math.min(minBelief, writeBeliefBuffer(agentIndex))
            existsStableAgent = existsStableAgent && (isStable && timesStable(i) > 1)
            i += 1
        }
        round += 1
        if (runMetadata.saveMode.includesRounds) snapshotAgentState(false, readBeliefBuffer)
        context.parent ! AgentUpdated(maxBelief, minBelief, existsStableAgent)
    }
    
    private def snapshotAgentState(forceSnapshot: Boolean, pastBeliefs: Array[Float]): Unit = {
        val roundDataSpeaking: ArrayBuffer[AgentState] = new ArrayBuffer[AgentState](numberOfAgents / 2)
        val roundDataSilent: ArrayBuffer[AgentState] = new ArrayBuffer[AgentState](numberOfAgents / 4)
        var silent = 0
        var speaking = 0
        var i = 0
        while (i < numberOfAgents) {
            if (forceSnapshot || math.abs(belief(i) - pastBeliefs(i + startsAt)) != 0) {
                silenceEffect(i).encodeOptionalValues(encoder)
                silenceStrategy(i).encodeOptionalValues(encoder)
                if (isSpeaking(i)) {
                    roundDataSpeaking.addOne(AgentState(ids(i + startsAt), belief(i), encoder.getBytes))
                } else {
                    roundDataSilent.addOne(AgentState(ids(i + startsAt), belief(i), encoder.getBytes))
                }
                encoder.reset()
            }
            i += 1
        }
        RoundRouter.getRoute ! AgentStatesSpeaking(roundDataSpeaking, round)
        RoundRouter.getRoute ! AgentStatesSilent(roundDataSilent, round)
    }
    
    inline final def congruent(neighborBelief: Float, i: Int): Unit = {
        if ((belief(i) - halfRange(i)) <= neighborBelief && neighborBelief <= (belief(i) + halfRange(i)))
            inFavor(i) += 1
        else 
            against(i) += 1
    }
    
    inline private def ensureCapacity(location: Int): Unit = {
        if (neighborsSize(location) >= neighborsRefs(location).length) {
            val newCapacity = neighborsRefs(location).length * 2
            neighborsRefs(location) = Array.copyOf(neighborsRefs(location), newCapacity)
            neighborsWeights(location) = Array.copyOf(neighborsWeights(location), newCapacity)
            neighborBiases(location) = Array.copyOf(neighborBiases(location), newCapacity)
        }
    }
    
    private def addNeighbor(agent: Int, neighbor: Int, influence: Float = 0.0f,
                            biasType: CognitiveBiasType = CognitiveBiasType.DeGroot): Unit = {
        ensureCapacity(agent)
        neighborsRefs(agent)(neighborsSize(agent)) = neighbor
        neighborsWeights(agent)(neighborsSize(agent)) = influence
        neighborBiases(agent)(neighborsSize(agent)) = biasType
        neighborsSize(agent) += 1
    }
    
    private def getBias(neighbor: Int, location: Int): CognitiveBiasType = {
        var i = 0
        while (i < neighborsSize(location)) {
            if (neighborsRefs(location)(i) == neighbor) return neighborBiases(location)(i)
            i += 1
        }
        CognitiveBiasType.DeGroot
    }
    
    private def generateInfluences(): Unit = {
        val random = new Random
        var i = 0
        while (i < numberOfAgents) {
            val totalSize = neighborsSize(i) + 1
            
            val randomNumbers = Array.fill(totalSize)(random.nextFloat())
            val sum = randomNumbers.sum
            
            var j = 0
            while (j < neighborsSize(i)) {
                neighborsWeights(i)(j) = randomNumbers(j) / sum
                j += 1
            }
            
            i += 1
        }
        
        hasUpdatedInfluences = true
    }
    
    override def preStart(): Unit = {
        runMetadata.distribution match {
            case Uniform =>
                val random = new Random
                var i = 0
                while (i < numberOfAgents - 1) {
                    belief(i) = random.nextFloat()
                    belief(i + 1) = random.nextFloat()
                    i += 2
                }
                
                if (i < numberOfAgents) {
                    belief(i) = random.nextFloat()
                }
            
            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution
            
            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
            
            case BiModal(peak1, peak2, lower, upper) =>
            
            case _ =>
            
        }
    }
}
