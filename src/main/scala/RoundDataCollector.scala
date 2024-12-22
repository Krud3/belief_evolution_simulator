import akka.actor.Actor

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.ByteBuffer
import scala.concurrent.duration.DurationInt
import play.api.libs.json.Json

import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}

case class AgentState
(
    agentId: UUID,
    round: Int,
    belief: Float,
    stateData: Option[java.util.HashMap[String, Float]]
)

case class AgentStateSpeaking(agentState: AgentState)
case class AgentStateSilent(agentState: AgentState)

class RoundDataCollector(tableNumber: Int, totalCollectors: Int) extends Actor {
    private case object CheckActivity
    private var saveThreshold = 2_000_000
    private var flushThreshold = 3
    
    private val silentStream = new StreamBuffer(100_000)
    private val speakingStream = new StreamBuffer(100_000)
    
    private var saveCount = 0
    private var receivedCount = 0
    private var tablesFlushedCount = 0
    
    private var hasUpdated = true
    private var shouldFlush = false
    
    implicit val executionContext: ExecutionContextExecutor = context.dispatcher
    context.system.scheduler.scheduleWithFixedDelay(
        initialDelay = 3.second,
        delay = 5.second,
        receiver = self,
        message = CheckActivity
    )
    
    def receive: Receive = {
        case AgentStateSpeaking(agentState) =>
            receivedCount += 1
            speakingStream.addRow(agentState)
            if (receivedCount >= saveThreshold) flush()
            hasUpdated = true
        
        
        case AgentStateSilent(agentState) =>
            receivedCount += 1
            silentStream.addRow(agentState)
            if (receivedCount >= saveThreshold) flush()
            hasUpdated = true
        
        case SaveRemainingData | CheckActivity =>
            if (receivedCount > 0)
                println(s"${self.path.name} yet to flush table${tableNumber + (totalCollectors * tablesFlushedCount)}")
            if (!hasUpdated) {
                saveCount = flushThreshold
                if (shouldFlush) flush(true)
                shouldFlush = false
            } else {
                if (receivedCount > 0) shouldFlush = true
            }
            hasUpdated = false
    }
    
    private def flush(forceFlush: Boolean = false): Unit = {
        if (receivedCount == 0) return
        
        val silentData = silentStream.finish()
        val speakingData = speakingStream.finish()
        val silentTableName = s"temp_silent_${tableNumber + (totalCollectors * tablesFlushedCount)}"
        val speakingTableName = s"temp_speaking_${tableNumber + (totalCollectors * tablesFlushedCount)}"
        
        DatabaseManager.insertRounds(silentData, silentTableName)
        DatabaseManager.insertRounds(speakingData, speakingTableName)
        
        saveCount += 1
        
        if (saveCount >= flushThreshold || forceFlush) {
            import context.dispatcher
            val speakingFlush = Future {
                DatabaseManager.flushRoundTable(speakingTableName, "agent_states_speaking")
            }
            val silentFlush = Future {
                DatabaseManager.flushRoundTable(silentTableName, "agent_states_silent")
            }
            saveCount = 0
            tablesFlushedCount = (tablesFlushedCount % 65535) + 1
            Future.sequence(Seq(speakingFlush, silentFlush)).foreach { _ =>
                println(s"Finished Flushing tables${tableNumber + (totalCollectors * (tablesFlushedCount - 1))}  " +
                  s"$tableNumber-$totalCollectors-$tablesFlushedCount")
            }
        }
        receivedCount = 0
        silentStream.reset()
        speakingStream.reset()
    }
}

class StreamBuffer(bufferSize: Int) {
    private var buffer = new ByteArrayOutputStream(bufferSize)
    private var dataOut = new DataOutputStream(buffer)
    writeHeader()
    
    def reset(): Unit = {
        buffer = new ByteArrayOutputStream(bufferSize)
        dataOut = new DataOutputStream(buffer)
        writeHeader()
    }
    
    private def writeHeader(): Unit = {
        dataOut.writeBytes("PGCOPY\n")
        dataOut.write(0xff)
        dataOut.write(0x0d)
        dataOut.write(0x0a)
        dataOut.write(0x00)
        dataOut.writeInt(0)
        dataOut.writeInt(0)
    }
    
    def addRow(agentState: AgentState): Unit = {
        // Write number of fields
        dataOut.writeShort(4)
        
        // Write UUID
        dataOut.writeInt(16)
        val uuidBytes = ByteBuffer.allocate(16)
          .putLong(agentState.agentId.getMostSignificantBits)
          .putLong(agentState.agentId.getLeastSignificantBits)
          .array()
        dataOut.write(uuidBytes)
        
        // Write round
        dataOut.writeInt(4)
        dataOut.writeInt(agentState.round)
        
        // Write belief
        dataOut.writeInt(4)
        dataOut.writeFloat(agentState.belief)
        
        // Write state data as JSON
        if (agentState.stateData eq None) {
            dataOut.writeInt(-1)
        } else {
            val map = agentState.stateData.get
            
            val estimatedSize = map.size * 30 // Estimate capacity 22 (max) + 8 chars for float
            val sb = new StringBuilder(estimatedSize)
            sb.append('{')
            
            val entries = map.entrySet()
            val iter = entries.iterator()
            if (iter.hasNext) {
                val entry = iter.next()
                sb.append('"').append(entry.getKey).append("\":").append(entry.getValue)
                
                while (iter.hasNext) {
                    val entry = iter.next()
                    sb.append(',').append('"').append(entry.getKey).append("\":").append(entry.getValue)
                }
            }
            sb.append('}')
            
            val jsonData = sb.toString.getBytes("UTF-8")
            
            // For non-null JSON, write length followed by data
            dataOut.writeInt(jsonData.length)
            dataOut.write(jsonData)
        }
    }
    
    def finish(): Array[Byte] = {
        dataOut.writeShort(-1)
        dataOut.flush()
        buffer.toByteArray
    }
}

