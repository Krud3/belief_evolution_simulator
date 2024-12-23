trait SilenceStrategy {
    def determineSilence(inFavor: Int, against: Int): Boolean
    def getOptionalValues: Option[Array[(String, Float)]]
}

class DeGrootSilenceStrategy extends SilenceStrategy {
    inline override def determineSilence(inFavor: Int, against: Int): Boolean = true
    override def toString: String = "DeGroot"
    override def getOptionalValues: Option[Array[(String, Float)]] = None
}

class MajoritySilence extends SilenceStrategy {
    inline override def determineSilence(inFavor: Int, against: Int): Boolean = {
        inFavor >= against
    }
    override def toString: String = "Majority"
    override def getOptionalValues: Option[Array[(String, Float)]] = None
}

class ThresholdSilence(threshold: Float) extends SilenceStrategy {
    inline override def determineSilence(inFavor: Int, against: Int): Boolean = {
        threshold * (inFavor + against) >= inFavor.toFloat
    }
    override def toString: String = "Threshold"
    override def getOptionalValues: Option[Array[(String, Float)]] = None
}

class ConfidenceSilence(threshold: Float, openMindedness: Int) extends SilenceStrategy {
    private var confidenceUnbounded: Float = -1f
    private var opinionClimate: Float = -1f
    
    //ToDo optimize confidence calculation
    override def determineSilence(inFavor: Int, against: Int): Boolean = {
        opinionClimate = inFavor + against match {
            case 0 => 0.0f
            case totalSpeaking => (inFavor - against).toFloat / totalSpeaking
        }
        confidenceUnbounded = math.max(confidenceUnbounded + opinionClimate, 0)
        val confidence = (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f
        confidence >= threshold
    }
    
    def getConfidence: Float = (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f
    override def toString: String = "Confidence"
    override def getOptionalValues: Option[Array[(String, Float)]] = Some(
        Array(("confidenceUnbounded", confidenceUnbounded),
              ("opinionClimate", opinionClimate)))
}

enum SilenceStrategyType:
    case DeGroot
    case Majority
    case Threshold(threshold: Float)
    case Confidence(threshold: Float, openMindedness: Int)

object SilenceStrategyFactory:
    def create(strategyType: SilenceStrategyType): SilenceStrategy = strategyType match
        case SilenceStrategyType.DeGroot => DeGrootSilenceStrategy()
        case SilenceStrategyType.Majority => MajoritySilence()
        case SilenceStrategyType.Threshold(threshold) => ThresholdSilence(threshold)
        case SilenceStrategyType.Confidence(threshold, openMindedness) =>
            ConfidenceSilence(threshold, openMindedness)