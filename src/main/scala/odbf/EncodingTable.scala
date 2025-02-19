package odbf

import scala.collection.mutable.ArrayBuffer

/**
 * Table encoding
 * Table entries:
 * [(id: u8, dataType: u8, length: u16, name: bytes)]
 * ids from [0-254] 255 reserved for id not found
 * dataType from [0-254] 255 reserved for data type not found (0 int, 1 float)
 * length(in Bytes) from [0 - 65534] 65535 reserved for out of bounds
 * name String UTF8 up to 128 characters
 */
object EncodingTable {
    var tableEntries: ArrayBuffer[(Byte, Byte, Short, String)] = ArrayBuffer.empty
    initializeWithDefaults()
    
    def addEntry(id: Byte, dataType: Byte, length: Short, name: String): Unit = {
        if (name.length > 128) throw Exception(StringIndexOutOfBoundsException())
        tableEntries.addOne(id, dataType, length, name)
    }
    
    def getLength(id: Byte): Short = {
        var i = 0
        while (i < tableEntries.length) {
            if (id == tableEntries(i)._1) return tableEntries(i)._3
            i += 1
        }
        -1
    }
    
    def getId(name: String): Byte = {
        var i = 0
        while (i < tableEntries.length) {
            if (name == tableEntries(i)._4) return tableEntries(i)._1
            i += 1
        }
        -1
    }
    
    def getEntry(id: Byte): (Byte, Byte, Short, String) = {
        var i = 0
        while (i < tableEntries.length) {
            if (id == tableEntries(i)._1) return tableEntries(i)
            i += 1
        }
        (-1, -1, -1, "")
    }
    
    def saveToDatabase(): Unit = {
        
    }
    
    def initializeWithDefaults(): Unit = {
        tableEntries.addOne(0, 1, 4, "publicBelief")
        tableEntries.addOne(1, 1, 4, "confidence")
        tableEntries.addOne(2, 1, 4, "opinionClimate")
    }
    
}

