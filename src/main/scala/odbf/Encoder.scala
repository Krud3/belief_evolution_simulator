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
    
    @inline
    def encode(name: String, value: AnyVal): Unit = {
        buffer.put(EncodingTable.getId(name))
        value match {
            case f: Float => buffer.putFloat(f)
            case i: Int => buffer.putInt(i)
        }
    }
    
    @inline
    def encodeFloat(name: String, value: Float): Unit = {
        buffer.put(EncodingTable.getId(name)).putFloat(value)
    }
    
    @inline
    def encodeInt(name: String, value: Int): Unit = {
        buffer.put(EncodingTable.getId(name)).putInt(value)
    }
    
    @inline
    def reset(): Unit = buffer.clear()
    
    @inline
    def getBytes: ByteBuffer = {
        buffer.flip()
        val copy = ByteBuffer.allocate(buffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        copy.put(buffer.duplicate())
        copy.flip()
        copy
    }
}


/**
 * Encoder for the Optional Data Binary Format
 * Single entry:
 * [table_id: u8][value: 0-65535 bytes]
 */
class EncoderSafe {
    var buffer: ByteBuffer = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
    
    def reSize(): Unit = {
        val newBuffer = ByteBuffer.allocate(buffer.capacity * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.flip()  // Prepare for reading
        newBuffer.put(buffer)  // Copy existing data
        buffer = newBuffer
    }
    
    def encode(id: Byte, value: AnyVal): Unit = {
        val valueLength = EncodingTable.getLength(id)
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
