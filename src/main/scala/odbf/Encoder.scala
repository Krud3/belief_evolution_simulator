package odbf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encoder for the Optional Data Binary Format
 * Single entry:
 * [table_id: u8][value: 0-65535 bytes]
 */
class Encoder(capacity: Int = 15) { 
    val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
    val encodingTable = EncodingTable()
    encodingTable.initializeWithDefaults()
    
    @inline
    def encode(name: String, value: AnyVal): Unit = {
        buffer.put(encodingTable.getId(name))
        value match {
            case f: Float => buffer.putFloat(f)
            case i: Int => buffer.putInt(i)
        }
    }
    
    @inline
    def encodeFloat(name: String, value: Float): Unit = {
        buffer.put(encodingTable.getId(name)).putFloat(value)
    }
    
    @inline
    def encodeInt(name: String, value: Int): Unit = {
        buffer.put(encodingTable.getId(name)).putInt(value)
    }
    
    @inline
    def reset(): Unit = buffer.clear()
    
    @inline
    def getBytes: ByteBuffer = {
        buffer.flip()
        buffer.slice()
    }
}


/**
 * Encoder for the Optional Data Binary Format
 * Single entry:
 * [table_id: u8][value: 0-65535 bytes]
 */
class EncoderSafe {
    var buffer: ByteBuffer = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN) 
    var encodingTable: EncodingTable = EncodingTable()
    encodingTable.initializeWithDefaults()
    
    def reSize(): Unit = {
        val newBuffer = ByteBuffer.allocate(buffer.capacity * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.flip()  // Prepare for reading
        newBuffer.put(buffer)  // Copy existing data
        buffer = newBuffer
    }
    
    def encode(id: Byte, value: AnyVal): Unit = {
        val valueLength = encodingTable.getLength(id)
        if (valueLength == -1) throw new Exception(s"Invalid id: $id")
        
        if (buffer.remaining() < 1 + valueLength) reSize()
        
        buffer.put(id)
        value match {
            case f: Float => buffer.putFloat(f)
            case i: Int => buffer.putInt(i)
            case _ => throw new Exception("Unsupported type")
        }
    }
    
    def getBytes: ByteBuffer = {
        buffer.flip()  // Prepare buffer for reading
        buffer.slice() // Get a view of the written data
    }
}
