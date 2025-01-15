package customTypes

import scala.annotation.targetName

case class u8 private (value: Int) {
    def toByte: Byte = value.toByte
    def toInt: Int = value
    
    // Arithmetic operations (with overflow checking)
    @targetName("plus")
    def +(other: u8): Option[u8] = u8(value + other.value)
    @targetName("minus")
    def -(other: u8): Option[u8] = u8(value - other.value)
}

object u8 {
    // Safe constructor
    def apply(value: Int): Option[u8] =
        if (value >= 0 && value <= 255) Some(new u8(value))
        else None
    
    // Unsafe constructor - throws exception if invalid
    def unsafe(value: Int): u8 = {
        require(value >= 0 && value <= 255, "Value must be between 0 and 255")
        new u8(value)
    }
    
    // Constructor from byte
    def fromByte(b: Byte): u8 = new u8(b & 0xFF)
}
