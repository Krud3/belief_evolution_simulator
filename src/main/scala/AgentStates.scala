final class AgentStates(numAgents: Int) {
    // Calculate number of bytes needed, rounding up
    private val states = Array.fill[Byte]((numAgents + 7) / 8)(-1.toByte)
    
    // Lookup table for quick float conversion
    private val bitToFloat = Array(0.0f, 1.0f)
    
    // Core bit operations
    @inline def getStateAsBoolean(agentIndex: Int): Boolean = {
        val byteIndex = agentIndex >>> 3    // Divide by 8
        val bitPosition = agentIndex & 7    // Modulo 8
        (states(byteIndex) & (1 << bitPosition)) != 0
    }
    
    @inline def getStateAsFloat(agentIndex: Int): Float = {
        val byteIndex = agentIndex >>> 3
        val bitPosition = agentIndex & 7
        bitToFloat((states(byteIndex) & (1 << bitPosition)) >> bitPosition)
    }
    
    @inline def getStateAsFloatWithMemory(agentIndex: Int, hasMemory: Boolean): Float = {
        if (hasMemory) 1.0f else {
            val byteIndex = agentIndex >>> 3
            val bitPosition = agentIndex & 7
            bitToFloat((states(byteIndex) & (1 << bitPosition)) >> bitPosition)
        }
    }
    
    def toggleState(agentIndex: Int): Unit = {
        val byteIndex = agentIndex >>> 3
        val bitPosition = agentIndex & 7
        states(byteIndex) = (states(byteIndex) ^ (1 << bitPosition)).toByte
    }
    
    def setState(agentIndex: Int, speaking: Boolean): Unit = {
        val byteIndex = agentIndex >>> 3
        val bitPosition = agentIndex & 7
        val mask = 1 << bitPosition
        states(byteIndex) = (
          if (speaking) states(byteIndex) | mask
          else states(byteIndex) & ~mask
          ).toByte
    }
    
    // Utility methods
    def clear(): Unit = {
        java.util.Arrays.fill(states, 0.toByte)
    }
    
    def capacity: Int = numAgents
}
