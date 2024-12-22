enum CognitiveBiasType:
    case DeGroot
    case Confirmation
    case Backfire
    case Authority
    case Insular

object CognitiveBiases {
    def DeGroot(beliefDifference: Float): Float = beliefDifference
    
    def confirmation(beliefDifference: Float): Float = {
        (beliefDifference * (1f + 0.0001f - math.abs(beliefDifference))) / (1f + 0.0001f)
    }
    
    def backfire(beliefDifference: Float): Float = {
        -(math.pow(beliefDifference, 3).toFloat)
    }
    
    def authority(beliefDifference: Float): Float = {
        beliefDifference match {
            case 0 => 0.0f
            case _ => beliefDifference / math.abs(beliefDifference)
        }
    }
    
    def insular(beliefDifference: Float): Float = 0f
    
    def applyBias(biasType: CognitiveBiasType, beliefDifference: Float): Float = {
        biasType match {
            case CognitiveBiasType.DeGroot => CognitiveBiases.DeGroot(beliefDifference)
            case CognitiveBiasType.Confirmation => CognitiveBiases.confirmation(beliefDifference)
            case CognitiveBiasType.Backfire => CognitiveBiases.backfire(beliefDifference)
            case CognitiveBiasType.Authority => CognitiveBiases.authority(beliefDifference)
            case CognitiveBiasType.Insular => CognitiveBiases.insular(beliefDifference)
        }
    }
}