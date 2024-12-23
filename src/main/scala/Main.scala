import SilenceEffectType.DeGroot
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

import scala.reflect

// Distributions
sealed trait Distribution {
    def toString: String
}

case object CustomDistribution extends Distribution {
    override def toString: String = "custom"
}

case object Uniform extends Distribution {
    override def toString: String = "uniform"
}

case class Normal(mean: Double, std: Double) extends Distribution {
    override def toString: String = s"normal(mean=$mean, std=$std)"
}

case class Exponential(lambda: Double) extends Distribution {
    override def toString: String = s"exponential(lambda=$lambda)"
}

case class BiModal(peak1: Double, peak2: Double, lower: Double = 0, upper: Double = 1) extends Distribution {
    override def toString: String = s"biModal(peak1=$peak1, peak2=$peak2, lower=$lower, upper=$upper)"
    
    val bimodalDistribution: BimodalDistribution = BimodalDistribution(peak1, peak2, lower, upper)
}

object Distribution {
    def fromString(s: String): Option[Distribution] = s match {
        case "customDistribution" => Some(CustomDistribution)
        case "uniform" => Some(Uniform)
        case str if str.startsWith("normal") =>
            val params = str.stripPrefix("normal(mean=").stripSuffix(")").split(", std=")
            if (params.length == 2) Some(Normal(params(0).toDouble, params(1).toDouble)) else None
        case str if str.startsWith("exponential") =>
            val param = str.stripPrefix("exponential(lambda=").stripSuffix(")")
            Some(Exponential(param.toDouble))
        case str if str.startsWith("biModal") =>
            None
        case _ => None
    }
}


// Run metadata


// Global control

def normalizeByWeightedSum(arr: Array[(String, Float)], selfInfluence: Float = 0f): Array[(String, Float)] = {
    if (arr.isEmpty) return arr
    
    val totalSum = arr.map(_._2).sum + selfInfluence
    if (totalSum == 0) return arr
    
    arr.map { case (str, value) =>
        (str, value / totalSum)
    }
}

// Custom network inserting functions
//def simulateFromPreviousNetwork(id: UUID, silenceStrategy: SilenceStrategy, silenceEffect: SilenceEffect, 
//                                iterationLimit: Int, name: String):
//AddSpecificNetwork = {
//    AddSpecificNetwork(
//        agents = DatabaseManager.reRunSpecificNetwork(id, agentType),
//        stopThreshold = 0.0001,
//        iterationLimit = iterationLimit,
//        name = name
//    )
//}

//def simulateFromCustomExample(beliefs: Array[Float], selfInfluence: Array[Float] = Array.emptyFloatArray,
//                              influences: Array[Array[(String, Float)]], silenceStrategy: SilenceStrategy,
//                              silenceEffect: SilenceEffect, iterationLimit: Int, name: String, 
//                              tolerances: Array[Float] = Array.empty[Float]): AddSpecificNetwork = {
//    val agents: Array[AgentInitialState] = Array.ofDim(beliefs.length)
//    
//    for (i <- beliefs.indices) {
//        agents(i) = AgentInitialState(
//            name = s"Agent${i + 1}",
//            initialBelief = beliefs(i),
//            agentType = agentType,
//            if (selfInfluence.isEmpty) influences(i)
//            else normalizeByWeightedSum(influences(i), selfInfluence(i)),
//            if (tolerances.nonEmpty) tolerances(i)
//            else 0.1, // Default tolerance value
//        )
//    }
//    
//    AddSpecificNetwork(
//        agents = agents,
//        stopThreshold = 0.001,
//        iterationLimit = iterationLimit,
//        name = name
//    )
//}

object globalTimer {
    val timer: CustomTimer = new CustomTimer()
}

object Mains extends App {
    val system = ActorSystem("original", ConfigFactory.load().getConfig("app-dispatcher"))
    val monitor = system.actorOf(Props(new Monitor), "Monitor")
    
    val density = 3
    val numberOfNetworks = 1_000
    val numberOfAgents = 10_000
    
    globalTimer.timer.start()
    monitor ! AddNetworks(
        agentTypeCount = Array((SilenceStrategyType.Majority, SilenceEffectType.Memoryless, numberOfAgents)),
        agentBiases = Array((CognitiveBiasType.DeGroot, 1.0f)),
        distribution = Uniform,
        saveMode = Agentless, //Agentless
        recencyFunction = None,
        numberOfNetworks = numberOfNetworks,
        density = density,
        iterationLimit = 1000,
        degreeDistribution = 2.5f,
        stopThreshold = 0.001f
    )

//    val customRun = AddSpecificNetwork(
//        Array(
//            AgentInitialState("Agent1", 1f, 0.2, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless),
//            AgentInitialState("Agent2", 0.8f, 0.15, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless),
//            AgentInitialState("Agent3", 0.5f, 0.1, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless),
//            AgentInitialState("Agent4", 0.2f, 0.15, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless),
//            AgentInitialState("Agent5", 0.0f, 0.2, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memoryless)
//        ),
//        Array(
//            Neighbors("Agent1", "Agent2", 0.4f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent3", 0.1f, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent2", "Agent1", 0.4f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent3", 0.1f, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent3", "Agent1", 0.2f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent2", 0.2f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent4", 0.2f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent5", 0.2f, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent4", "Agent3", 0.1f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent5", 0.4f, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent5", "Agent3", 0.1f, CognitiveBiasType.DeGroot),
//            Neighbors("Agent5", "Agent4", 0.4f, CognitiveBiasType.DeGroot),
//        ),
//        CustomDistribution,
//        Full,
//        0.001f,
//        1000,
//        "First_try",
//        None
//    )
//
//     monitor ! customRun
//
    var outer_weak = 0.21f
    var outer_strong = 0.15f
    var inner_weak = 0.4f
    var inner_strong = 0.32f
//    val publicRun1 = AddSpecificNetwork(
//        Array(
//            AgentInitialState("Agent1", 1f, 0.2, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent2", 0.9f, 0.1, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent3", 0.1f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent4", 0f, 0.15, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory)
//            ),
//        Array(
//            Neighbors("Agent1", "Agent2", inner_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent3", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent4", outer_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent2", "Agent1", inner_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent3", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent4", outer_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent3", "Agent1", outer_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent2", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent4", inner_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent4", "Agent1", outer_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent2", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent3", inner_weak, CognitiveBiasType.DeGroot)
//            ),
//        CustomDistribution,
//        Full,
//        0.001f,
//        1000,
//        "First_try",
//        None
//        )
    
//    outer_weak = 0.4f
//    outer_strong = 0.15f
//    inner_weak = 0.35f
//    inner_strong = 0.15f
//    val paperExample1 = AddSpecificNetwork(
//        Array(
//            AgentInitialState("Agent1", 1f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent2", 0.9f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent3", 0.1f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory),
//            AgentInitialState("Agent4", 0f, 0.05, 0.0, SilenceStrategyType.Majority, SilenceEffectType.Memory)
//            ),
//        Array(
//            Neighbors("Agent1", "Agent2", inner_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent3", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent1", "Agent4", outer_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent2", "Agent1", inner_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent3", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent2", "Agent4", outer_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent3", "Agent1", outer_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent2", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent3", "Agent4", inner_strong, CognitiveBiasType.DeGroot),
//
//            Neighbors("Agent4", "Agent1", outer_strong, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent2", outer_weak, CognitiveBiasType.DeGroot),
//            Neighbors("Agent4", "Agent3", inner_weak, CognitiveBiasType.DeGroot)
//            ),
//        CustomDistribution,
//        Full,
//        0.001f,
//        1000,
//        "First_try",
//        None
//        )
//
//     monitor ! paperExample1
    
    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
}

