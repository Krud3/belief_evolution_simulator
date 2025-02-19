import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import datastructures.ArrayListInt
import odbf.Encoder

import java.util.UUID
import scala.util.Random
import scala.concurrent.duration.*
import scala.collection.mutable.ArrayBuffer


// DeGroot based Agent base

// Messages
case class SetInitialState(
    belief: Float, 
    toleranceRadius: Float, 
    toleranceOffset: Float, 
    agentIndex: Int
) // Network -> Agent

case object AddNames // Network -> Agent
case object MarkAsCustomRun // Network -> Agent
case object UpdateAgent1R // Network -> Agent
case object UpdateAgent2R // Network -> Agent
case object SnapShotAgent // Network -> Agent
case class FirstUpdate(neighborSaver: ActorRef, staticSaver: ActorRef, agents: Array[ActorRef]) // Network -> Agent

case class SendBelief(belief: Float) extends AnyVal // Agent -> self
case class SendInfluenceReduced(belief: Float, influenceRedux: Float) // Agent -> self
case object Silent // Agent -> self

case class AddNeighbors(neighbors: Array[ArrayListInt]) // Network -> agent


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
// Optimize loop indexes to start at startsAt and end at startsAt + numberOfAgents

// New Gen Agent
class Agent(
    ids: Array[UUID], silenceStrategy: Array[SilenceStrategy], silenceEffect: Array[SilenceEffect],
    runMetadata: RunMetadata, beliefBuffer1: Array[Float], beliefBuffer2: Array[Float],
    speakingBuffer1: AgentStates, speakingBuffer2: AgentStates, belief: Array[Float], 
    tolRadius: Array[Float], tolOffset: Array[Float], indexOffset: Array[Int], timesStable: Array[Int], 
    inFavor: Array[Int], against: Array[Int], hasMemory: Array[Boolean], neighborsRefs: Array[Int], 
    neighborsWeights: Array[Float], neighborBiases: Array[BiasFunction], numberOfAgents: Int,
    startsAt: Int, names: Array[String]
)
  extends Actor {
    
    // Initialize hasMemory
    var i: Int = startsAt
    while (i < (numberOfAgents + startsAt)) {
        hasMemory(i) = silenceEffect(i).isInstanceOf[MemoryEffect]
        i += 1
    }
    
    // Scalar fields
    val encoder: Encoder = Encoder()
    implicit val timeout: Timeout = Timeout(600.seconds) // 8 bytes
    var round: Int = 0
    var hasUpdatedInfluences: Boolean = false
    var isGenerated: Boolean = true
    var bufferSwitch: Boolean = true // true = buffers 1, false = buffers 2
    
    def receive: Receive = {
        case MarkAsCustomRun =>
            hasUpdatedInfluences = true
            isGenerated = false
        
        case SetInitialState(initialBelief, toleranceRadius, toleranceOffset, agentIndex) =>
            belief(agentIndex) = initialBelief
            tolRadius(agentIndex) = toleranceRadius
            tolOffset(agentIndex) = toleranceOffset
        
        case FirstUpdate(neighborSaver, agentStaticDataSaver, agents) =>
            val neighborActors = new ArrayBuffer[NeighborStructure](numberOfAgents * 2)
            val agentsStaticStates = new Array[StaticAgentData](numberOfAgents)
            var i = startsAt
            while (i < (startsAt + numberOfAgents)) {
                beliefBuffer1(i) = belief(i)
                silenceEffect(i) match { case m: MemoryEffect => m.initialize(belief(i)) case _ => }
                if (!hasUpdatedInfluences) generateInfluencesAndBiases()
                if (runMetadata.saveMode.includesAgents) {
                    agentsStaticStates(i) = StaticAgentData(
                        id = ids(i),
                        numberOfNeighbors = neighborsSize(i),
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
                val radius = tolRadius(i)
                tolRadius(i) = radius + tolOffset(i) // Become upper
                tolOffset(i) = radius - tolOffset(i) // Become lower
                
                if (runMetadata.saveMode.includesNeighbors) {
                    var j = indexOffset(math.max(0, i - 1))
                    while (j < indexOffset(i)) {
                        neighborActors.addOne(NeighborStructure(
                            ids(i),
                            ids(neighborsRefs(j)),
                            neighborsWeights(j),
                            CognitiveBiases.toBiasType(neighborBiases(j))
                            ))
                        j += 1
                    }
                }
                i += 1
            }
            if (runMetadata.saveMode.includesFirstRound) snapshotAgentState(true, null, speakingBuffer1)
            if (runMetadata.saveMode.includesNeighbors) neighborSaver ! SendNeighbors(neighborActors)
            if (runMetadata.saveMode.includesAgents) agentStaticDataSaver ! SendStaticAgentData(agentsStaticStates)
            
            context.parent ! RunFirstRound
            
            
        case UpdateAgent1R =>
            // Update belief 1 = read buffers, 2 = write buffers
            bufferSwitch = true
            updateBuffers(beliefBuffer1, beliefBuffer2, speakingBuffer1, speakingBuffer2)
        
        case UpdateAgent2R =>
            // Update belief 2 = read buffers, 1 = write buffers
            bufferSwitch = false
            updateBuffers(beliefBuffer2, beliefBuffer1, speakingBuffer2, speakingBuffer1)
            
        case SnapShotAgent =>
            if (bufferSwitch) snapshotAgentState(true, null, speakingBuffer2)
            else snapshotAgentState(true, null, speakingBuffer1)
            context.parent ! ActorFinished
            context.stop(self)
            
    }
    
    private def updateBuffers(
        readBeliefBuffer: Array[Float],
        writeBeliefBuffer: Array[Float],
        readSpeakingBuffer: AgentStates,
        writeSpeakingBuffer: AgentStates
    ): Unit = {
        var maxBelief = -1f
        var minBelief = 2f
        var existsStableAgent = true
        var i = startsAt
        var sum0, sum1, sum2, sum3 = 0f
        while (i < (startsAt + numberOfAgents)) {
            // Zero out the sums
            sum0 = 0f; sum1 = 0f; sum2 = 0f; sum3 = 0f
            val hasMem = if (hasMemory(i)) 1 else 0
            val initialBelief = belief(i)
            
            var j = if (i > 0) indexOffset(i - 1) else 0
            while (j < (indexOffset(i) - 3)) {
                val b0: Float = readBeliefBuffer(neighborsRefs(j))
                val b1: Float = readBeliefBuffer(neighborsRefs(j + 1))
                val b2: Float = readBeliefBuffer(neighborsRefs(j + 2))
                val b3: Float = readBeliefBuffer(neighborsRefs(j + 3))

                val bias0: Float = neighborBiases(j)(b0 - initialBelief)
                val bias1: Float = neighborBiases(j + 1)(b1 - initialBelief)
                val bias2: Float = neighborBiases(j + 2)(b2 - initialBelief)
                val bias3: Float = neighborBiases(j + 3)(b3 - initialBelief)

                val speaking0: Float = readSpeakingBuffer.getStateAsFloatWithMemory(neighborsRefs(j), hasMem)
                val speaking1: Float = readSpeakingBuffer.getStateAsFloatWithMemory(neighborsRefs(j + 1), hasMem)
                val speaking2: Float = readSpeakingBuffer.getStateAsFloatWithMemory(neighborsRefs(j + 2), hasMem)
                val speaking3: Float = readSpeakingBuffer.getStateAsFloatWithMemory(neighborsRefs(j + 3), hasMem)

                // Sum calculations S * Bias(Bj - Bi) * I_j
                sum0 += speaking0 * bias0 * neighborsWeights(j)
                sum1 += speaking1 * bias1 * neighborsWeights(j + 1)
                sum2 += speaking2 * bias2 * neighborsWeights(j + 2)
                sum3 += speaking3 * bias3 * neighborsWeights(j + 3)

                if (speaking0 == 1f) congruent(b0, i)
                if (speaking1 == 1f) congruent(b1, i)
                if (speaking2 == 1f) congruent(b2, i)
                if (speaking3 == 1f) congruent(b3, i)

                j += 4
            }

            while (j < indexOffset(i)) {
                val b: Float = readBeliefBuffer(neighborsRefs(j))
                val bias: Float = neighborBiases(j)(b - initialBelief)
                val speaking: Int = readSpeakingBuffer.getStateAsIntWithMemory(neighborsRefs(j), hasMem)
                if (speaking == 1) congruent(b, i)
                
                belief(i) += speaking * bias * neighborsWeights(j)
                
                j += 1
            }
            
            belief(i) += sum0 + sum1 + sum2 + sum3
            val isSpeaking = silenceStrategy(i).determineSilence(inFavor(i), against(i))
            // Write to buffers
            val agentIndex = i
            writeSpeakingBuffer.setState(agentIndex, isSpeaking)
            writeBeliefBuffer(agentIndex) = silenceEffect(i).getPublicValue(belief(i), isSpeaking)
            val isStable = math.abs(belief(i) - initialBelief) < 0.0000001f // ToDo add a better threshold runMetadata.stopThreshold
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
        if (runMetadata.saveMode.includesRounds) snapshotAgentState(false, readBeliefBuffer, writeSpeakingBuffer)
        context.parent ! AgentUpdated(maxBelief, minBelief, existsStableAgent)
    }
    
    //
    private def snapshotAgentState(forceSnapshot: Boolean, pastBeliefs: Array[Float], 
        speakingState: AgentStates): Unit = {
        val roundDataSpeaking: ArrayBuffer[AgentState] = new ArrayBuffer[AgentState](numberOfAgents * 3 / 2)
        val roundDataSilent: ArrayBuffer[AgentState] = new ArrayBuffer[AgentState](numberOfAgents / 4)
        var i = startsAt
        while (i < (startsAt + numberOfAgents)) {
            if (forceSnapshot || math.abs(belief(i) - pastBeliefs(i)) != 0) {
                silenceEffect(i).encodeOptionalValues(encoder)
                silenceStrategy(i).encodeOptionalValues(encoder)
                if (speakingState.getStateAsBoolean(i)) {
                    roundDataSpeaking.addOne(AgentState(ids(i), belief(i), encoder.getBytes))
                } else {
                    roundDataSilent.addOne(AgentState(ids(i), belief(i), encoder.getBytes))
                }
                //println(encoder.getBytes.array().mkString(s"${i + startsAt} Array(", ", ", ")"))
                encoder.reset()
            }
            i += 1
        }
        RoundRouter.getRoute ! AgentStatesSpeaking(roundDataSpeaking, round)
        RoundRouter.getRoute ! AgentStatesSilent(roundDataSilent, round)
    }
    
    inline final def congruent3(neighborBelief: Float, i: Int): Unit = {
        if ((belief(i) - tolOffset(i)) <= neighborBelief && neighborBelief <= (belief(i) + tolRadius(i)))
            inFavor(i) += 1
        else 
            against(i) += 1
    }
    
    // Pending implement tolRadius -> precomputedUpper, tolOffset -> precomputedLower
    inline final def congruent2(neighborBelief: Float, i: Int): Unit = {
        val lower = neighborBelief - (belief(i) - tolOffset(i))
        val upper = belief(i) + tolRadius(i) - neighborBelief
        // If lower or upper is negative then it is outside the range
        val mask = (java.lang.Float.floatToRawIntBits(lower) |
          java.lang.Float.floatToRawIntBits(upper)) >>> 31
        against(i) += mask
        inFavor(i) += (mask ^ 1)
    }
    
    // ToDo optimize to not recompute belief(i) - tolOffset(i) every time
    inline final def congruent(neighborBelief: Float, i: Int): Unit = {
        val mask = (java.lang.Float.floatToRawIntBits(neighborBelief - (belief(i) - tolOffset(i))) |
          java.lang.Float.floatToRawIntBits(belief(i) + tolRadius(i) - neighborBelief)) >>> 31
        against(i) += mask
        inFavor(i) += (mask ^ 1)
    }
    
    @inline
    def neighborsSize(i: Int): Int = {
        if (i == 0) indexOffset(i)
        else indexOffset(i) - indexOffset(i - 1)
    }
    
    private def generateInfluencesAndBiases(): Unit = {
        val random = new Random
        var i = startsAt
        while (i < (startsAt + numberOfAgents)) {
            val randomNumbers = Array.fill(neighborsSize(i) + 1)(random.nextFloat())
            val sum = randomNumbers.sum
            
            var j = if (i != 0) indexOffset(i - 1) else 0
            var k = 0
            while (j < indexOffset(i)) {
                neighborsWeights(j) = randomNumbers(k) / sum
                neighborBiases(j) = CognitiveBiasType.DeGroot.toFunction
                j += 1
                k += 1
            }
            i += 1
        }
        hasUpdatedInfluences = true
//        println(indexOffset.mkString("offset(", ", ", ")"))
//        println(neighborsRefs.mkString("Neighbors(", ", ", ")"))
//        println(neighborsWeights.mkString("Influences(", ", ", ")\n"))
    }
    
    override def preStart(): Unit = {
        runMetadata.distribution match {
            case Uniform =>
                val random = new Random
                var i = startsAt
                while (i < (startsAt + numberOfAgents)) {
                    belief(i) = random.nextFloat()
                    i += 1
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
