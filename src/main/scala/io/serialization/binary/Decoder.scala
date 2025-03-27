package io.serialization.binary

object Decoder {
    def decode(byteArr: Array[Byte]): Array[Float] = {
        val decodedFloats = new Array[Float](3)
        var i = 0
        while (i < byteArr.length) {
            val entry = EncodingTable.getEntry(byteArr(i))
            decodedFloats(entry._1) = java.lang.Float.intBitsToFloat(
                  (byteArr(i + 4) & 0xff) << 24 |
                  (byteArr(i + 3) & 0xff) << 16 |
                  (byteArr(i + 2) & 0xff) << 8 |
                  (byteArr(i + 1) & 0xff)
                )
            i += 1 + entry._3
        }
        decodedFloats
    }
}
