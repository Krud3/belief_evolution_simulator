import odbf.Encoder

trait SilenceEffect {
    def getPublicValue(belief: Float, isSpeaking: Boolean): Float
    def encodeOptionalValues(encoder: Encoder): Unit
}

class DeGrootSilenceEffect extends SilenceEffect {
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    override def toString: String = "DeGroot"
    inline override def encodeOptionalValues(encoder: Encoder): Unit = {}
}

class MemoryEffect extends SilenceEffect {
    var publicBelief: Float = 2f
    def initialize(initValue: Float): Unit = {
        publicBelief = initValue
        println(s"Public Value: $publicBelief")
    }
    
    override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = {
        if (isSpeaking) publicBelief = belief
        publicBelief
    }
    
    override def toString: String = "Memory"
    @inline override def encodeOptionalValues(encoder: Encoder): Unit = {
        encoder.encodeFloat("publicBelief", publicBelief)
    }
}

class MemorylessEffect extends SilenceEffect {
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "Memoryless"
    override def encodeOptionalValues(encoder: Encoder): Unit = {}
}

class RecencyEffect(recencyFunction: (Float, Int) => Float) extends SilenceEffect {
    private var roundsSilent: Int = 0
    
    // ToDo implement recency effect
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "Recency"
    override def encodeOptionalValues(encoder: Encoder): Unit = {}
}

class PeersEffect(silenceEffect: SilenceEffect) extends SilenceEffect {
    private var neighborsAgreement: Array[Boolean] = new Array[Boolean](16)
    
    def updateNeighborAgreement(index: Int, value: Boolean): Unit = {
        neighborsAgreement(index) = value
    }
    
    // ToDo implement peers effect
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "Peers"
    override def encodeOptionalValues(encoder: Encoder): Unit = {}
}

enum SilenceEffectType:
    case DeGroot
    case Memory
    case Memoryless
    case Recency(recencyFunction: (Float, Int) => Float)
    case Peers(baseEffect: SilenceEffectType)

object SilenceEffectFactory:
    def create(effectType: SilenceEffectType): SilenceEffect = effectType match
        case SilenceEffectType.DeGroot => DeGrootSilenceEffect()
        case SilenceEffectType.Memory => MemoryEffect()
        case SilenceEffectType.Memoryless => MemorylessEffect()
        case SilenceEffectType.Recency(recencyFunction) => RecencyEffect(recencyFunction)
        case SilenceEffectType.Peers(baseEffect) => PeersEffect(create(baseEffect))