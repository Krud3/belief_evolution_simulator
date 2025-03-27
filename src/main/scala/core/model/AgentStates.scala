package core.model

final class AgentStates(numAgents: Int) {
    // Calculate number of bytes needed, rounding up
    val states = Array.fill[Int]((numAgents + 31) / 32)(-1)
    
    // Core bit operations
    @inline def getStateAsBoolean(agentIndex: Int): Boolean = {
        val byteIndex = agentIndex >>> 5 // Divide by 32
        val bitPosition = agentIndex & 31 // Modulo 32
        ((states(byteIndex) >>> bitPosition) & 1) != 0
    }
    
    @inline def getStateAsFloat(agentIndex: Int): Float = {
        val byteIndex = agentIndex >>> 5    // Divide by 8
        val bitPosition = agentIndex & 31    // Modulo 8
        ((states(byteIndex) >>> bitPosition) & 1).toFloat
    }
    
    @inline def getStateAsFloatWithMemory(agentIndex: Int, hasMemory: Int): Float = {
        val byteIndex = agentIndex >>> 5
        val bitPosition = agentIndex & 31
        (((states(byteIndex) >>> bitPosition) & 1) | hasMemory).toFloat
    }
    
    @inline def getStateAsIntWithMemory(agentIndex: Int, hasMemory: Int): Int = {
        val byteIndex = agentIndex >>> 5
        val bitPosition = agentIndex & 31
        ((states(byteIndex) >>> bitPosition) & 1) | hasMemory
    }
    
    def setState(agentIndex: Int, speaking: Boolean): Unit = {
        val byteIndex = agentIndex >>> 5
        val bitPosition = agentIndex & 31
        // ToDo mask = speaking << bitPosition
        // states(byteIndex) &= mask
        val mask = 1 << bitPosition
        states(byteIndex) = if (speaking) states(byteIndex) | mask
        else states(byteIndex) & ~mask
    }
    
    def toggleState(agentIndex: Int): Unit = {
        val byteIndex = agentIndex >>> 5
        val bitPosition = agentIndex & 31
        states(byteIndex) = (states(byteIndex) ^ (1 << bitPosition)).toByte
    }
    
    def setStateWide(agentIndex: Int, value: Byte): Unit = {
        val byteIndex = agentIndex >>> 5
        states(byteIndex) = value
    }
    
    // Utility methods
    def reset(): Unit = {
        java.util.Arrays.fill(states, -1.toByte)
    }
    
    def printBytes(): Unit = {
        states.foreach(b => println(String.format("%8s", Integer.toBinaryString(b & 0xFF)).replace(' ', '0')))
    }
    
    def capacity: Int = numAgents
}
