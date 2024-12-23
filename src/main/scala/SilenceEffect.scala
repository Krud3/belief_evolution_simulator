import akka.actor.ActorRef

trait SilenceEffect {
    def sendBeliefToNeighbors(neighborsRefs: Array[ActorRef], sentBy: ActorRef, neighborsSize: Int,
                              belief: Float, isSpeaking: Boolean): Unit
    
    def getPublicValue(belief: Float, isSpeaking: Boolean): Float
    
    def getOptionalValues: Option[(String, Float)]
}

class DeGrootSilenceEffect extends SilenceEffect {
    override def sendBeliefToNeighbors(neighborsRefs: Array[ActorRef], sentBy: ActorRef, neighborsSize: Int, 
                                       belief: Float, isSpeaking: Boolean): Unit = {
        var i = 0
        while (i < neighborsSize) {
            neighborsRefs(i).tell(SendBelief(belief), sentBy)
            i += 1
        }
    }
    
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "DeGroot"
    override def getOptionalValues: Option[(String, Float)] = None
}

class MemoryEffect extends SilenceEffect {
    var publicBelief: Float = 2f
    
    override def sendBeliefToNeighbors(neighborsRefs: Array[ActorRef], sentBy: ActorRef, neighborsSize: Int,
                                       belief: Float, isSpeaking: Boolean): Unit = {
        if (!isSpeaking || publicBelief == 2f) publicBelief = belief
        var i = 0
        while (i < neighborsSize) {
            neighborsRefs(i).tell(SendBelief(publicBelief), sentBy)
            i += 1
        }
    }
    
    override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = {
        if (isSpeaking || publicBelief == 2f) publicBelief = belief
        publicBelief
    }
    
    override def toString: String = "Memory"
    override def getOptionalValues: Option[(String, Float)] = Some("publicBelief", publicBelief)
}

class MemorylessEffect extends SilenceEffect {
    override def sendBeliefToNeighbors(neighborsRefs: Array[ActorRef], sentBy: ActorRef, neighborsSize: Int,
                                       belief: Float, isSpeaking: Boolean): Unit = {
        val msg = if (isSpeaking) SendBelief(belief) else Silent
        var i = 0
        while (i < neighborsSize) {
            neighborsRefs(i).tell(msg, sentBy)
            i += 1
        }
    }
    
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "Memoryless"
    override def getOptionalValues: Option[(String, Float)] = None
}

class RecencyEffect(recencyFunction: (Float, Int) => Float) extends SilenceEffect {
    private var roundsSilent: Int = 0
    
    override def sendBeliefToNeighbors(neighborsRefs: Array[ActorRef], sentBy: ActorRef, neighborsSize: Int,
                                       belief: Float, isSpeaking: Boolean): Unit = {
        roundsSilent += (if (isSpeaking) 0 else 1)
        val msg = if (recencyFunction(belief, roundsSilent) < 1) SendInfluenceReduced(
            belief, recencyFunction(belief, roundsSilent)) else Silent
        var i = 0
        while (i < neighborsSize) {
            neighborsRefs(i).tell(msg, sentBy)
            i += 1
        }
    }
    
    // ToDo implement recency effect
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "Recency"
    override def getOptionalValues: Option[(String, Float)] = None
}

class PeersEffect(silenceEffect: SilenceEffect) extends SilenceEffect {
    private var neighborsAgreement: Array[Boolean] = new Array[Boolean](16)
    
    override def sendBeliefToNeighbors(neighborsRefs: Array[ActorRef], sentBy: ActorRef, neighborsSize: Int,
                                       belief: Float, isSpeaking: Boolean): Unit = {
        var i = 0
        while (i < neighborsSize) {
            if (neighborsAgreement(i)) neighborsRefs(i).tell(SendBelief(belief), sentBy) 
            else neighborsRefs(i) ! silenceEffect.sendBeliefToNeighbors(neighborsRefs, sentBy, neighborsSize, belief, 
                isSpeaking)
            i += 1
        }
    }
    
    def updateNeighborAgreement(index: Int, value: Boolean): Unit = {
        neighborsAgreement(index) = value
    }
    
    // ToDo implement peers effect
    inline override def getPublicValue(belief: Float, isSpeaking: Boolean): Float = belief
    
    override def toString: String = "Peers"
    override def getOptionalValues: Option[(String, Float)] = None
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