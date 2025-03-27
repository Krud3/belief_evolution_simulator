package core.model.agent.behavior.bias

enum CognitiveBiasType:
    case DeGroot
    case Confirmation
    case Backfire
    case Authority
    case Insular
    
    def toFunction: BiasFunction = this match {
        case CognitiveBiasType.DeGroot => CognitiveBiases.DeGroot
        case CognitiveBiasType.Confirmation => CognitiveBiases.confirmation
        case CognitiveBiasType.Backfire => CognitiveBiases.backfire
        case CognitiveBiasType.Authority => CognitiveBiases.authority
        case CognitiveBiasType.Insular => CognitiveBiases.insular
    }

type BiasFunction = Float => Float

object CognitiveBiases {
    val DeGroot: BiasFunction = x => x
    val confirmation: BiasFunction = x => (x * (1f + 0.0001f - math.abs(x))) / (1f + 0.0001f)
    val backfire: BiasFunction = x => -(math.pow(x, 3).toFloat)
    val authority: BiasFunction = {
        case 0f => 0.0f
        case x => x / math.abs(x)
    }
    val insular: BiasFunction = _ => 0f
    
    def applyBias(biasType: CognitiveBiasType, beliefDifference: Float): Float = {
        biasType match {
            case CognitiveBiasType.DeGroot => CognitiveBiases.DeGroot(beliefDifference)
            case CognitiveBiasType.Confirmation => CognitiveBiases.confirmation(beliefDifference)
            case CognitiveBiasType.Backfire => CognitiveBiases.backfire(beliefDifference)
            case CognitiveBiasType.Authority => CognitiveBiases.authority(beliefDifference)
            case CognitiveBiasType.Insular => CognitiveBiases.insular(beliefDifference)
        }
    }
    
    def toBiasType(f: BiasFunction): CognitiveBiasType = {
        if (f eq CognitiveBiases.DeGroot) CognitiveBiasType.DeGroot
        else if (f eq CognitiveBiases.confirmation) CognitiveBiasType.Confirmation
        else if (f eq CognitiveBiases.backfire) CognitiveBiasType.Backfire
        else if (f eq CognitiveBiases.authority) CognitiveBiasType.Authority
        else if (f eq CognitiveBiases.insular) CognitiveBiasType.Insular
        else throw new IllegalArgumentException("Unknown bias function")
    }
    
    def fromString(bias: String): Option[CognitiveBiasType] = bias match {
        case "DeGroot" => Some(CognitiveBiasType.DeGroot)
        case "Confirmation" => Some(CognitiveBiasType.Confirmation)
        case "Backfire" => Some(CognitiveBiasType.Backfire)
        case "Authority" => Some(CognitiveBiasType.Authority)
        case "Insular" => Some(CognitiveBiasType.Insular)
        case _ => None
    }
}