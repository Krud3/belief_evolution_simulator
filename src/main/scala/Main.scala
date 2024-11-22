import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import com.typesafe.config.ConfigFactory

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
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


// Agent types
sealed trait AgentType

case object MemoryConfidence extends AgentType

case object MemoryMajority extends AgentType

case object MemoryLessConfidence extends AgentType

case object MemoryLessMajority extends AgentType


// Global control
case class AddNetworks
(
  numberOfNetworks: Int,
  numberOfAgents: Int,
  density: Int,
  degreeDistribution: Float,
  stopThreshold: Float,
  distribution: Distribution,
  iterationLimit: Int,
  agentTypeCount: Map[AgentType, Int]
)

case class AddSpecificNetwork
(
  agents: Array[AgentInitialState],
  stopThreshold: Float,
  iterationLimit: Int,
  name: String
)

case class AgentInitialState
(
  name: String,
  initialBelief: Float,
  agentType: AgentType,
  neighbors: Array[(String, Float)],
  tolerance: Float
)

def normalizeByWeightedSum(arr: Array[(String, Float)], selfInfluence: Float = 0f): Array[(String, Float)] = {
    if (arr.isEmpty) return arr
    
    val totalSum = arr.map(_._2).sum + selfInfluence
    if (totalSum == 0) return arr
    
    arr.map { case (str, value) =>
        (str, value / totalSum)
    }
}

// Custom network inserting functions
def simulateFromPreviousNetwork(id: UUID, agentType: AgentType, iterationLimit: Int, name: String):
AddSpecificNetwork = {
    AddSpecificNetwork(
        agents = DatabaseManager.reRunSpecificNetwork(id, agentType),
        stopThreshold = 0.0001,
        iterationLimit = iterationLimit,
        name = name
    )
}

def simulateFromCustomExample(beliefs: Array[Float], selfInfluence: Array[Float] = Array.emptyFloatArray,
                              influences: Array[Array[(String, Float)]], agentType: AgentType, iterationLimit: Int,
                              name: String, tolerances: Array[Float] = Array.empty[Float]): AddSpecificNetwork = {
    val agents: Array[AgentInitialState] = Array.ofDim(beliefs.length)
    
    for (i <- beliefs.indices) {
        agents(i) = AgentInitialState(
            name = s"Agent${i + 1}",
            initialBelief = beliefs(i),
            agentType = agentType,
            if (selfInfluence.isEmpty) influences(i)
            else normalizeByWeightedSum(influences(i), selfInfluence(i)),
            if (tolerances.nonEmpty) tolerances(i)
            else 0.1, // Default tolerance value
        )
    }
    
    AddSpecificNetwork(
        agents = agents,
        stopThreshold = 0.001,
        iterationLimit = iterationLimit,
        name = name
    )
}

class GreeterActor(name: String) extends Actor {
    def receive: Receive = {
        case _ =>
            println(s"Hello there, I'm $name")
    }
}

object globalTimer {
    val timer: CustomTimer = new CustomTimer()
}

object Mains extends App {
    val system = ActorSystem("original", ConfigFactory.load().getConfig("app-dispatcher"))
    val monitor = system.actorOf(Props(new Monitor), "Monitor")
    
    val density = 2
    val numberOfAgents = 1000000
    val numberOfNetworks = 10
    
    globalTimer.timer.start()
    var i = 1
    while (i <= density) {
//        monitor ! AddNetworks(
//            numberOfNetworks,
//            numberOfAgents,
//            i,
//            2.5f,
//            0.001,
//            Uniform,
//            1000,
//            Map(
//                MemoryLessConfidence -> numberOfAgents,
//                MemoryConfidence -> 0,
//                MemoryLessMajority -> 0,
//                MemoryMajority -> 0
//            )
//        )
//
        monitor ! AddNetworks(
            numberOfNetworks,
            numberOfAgents,
            i,
            2.5f,
            0.001,
            Uniform,
            1000,
            Map(
                MemoryLessConfidence -> 0,
                MemoryConfidence -> numberOfAgents,
                MemoryLessMajority -> 0,
                MemoryMajority -> 0
            )
        )
//
//        monitor ! AddNetworks(
//            numberOfNetworks,
//            numberOfAgents,
//            i,
//            2.5f,
//            0.001,
//            Uniform,
//            1000,
//            Map(
//                MemoryLessConfidence -> 0,
//                MemoryConfidence -> 0,
//                MemoryLessMajority -> numberOfAgents,
//                MemoryMajority -> 0
//            )
//        )
//
//        monitor ! AddNetworks(
//            numberOfNetworks,
//            numberOfAgents,
//            i,
//            2.5f,
//            0.001,
//            Uniform,
//            1000,
//            Map(
//                MemoryLessConfidence -> 0,
//                MemoryConfidence -> 0,
//                MemoryLessMajority -> 0,
//                MemoryMajority -> numberOfAgents
//            )
//        )
        
        i += 1
    }
    
    
    val msg = simulateFromPreviousNetwork(
        UUID.fromString("01907514-c3f4-7000-b676-e82bc785d2cc"),
        MemoryMajority,
        1500,
        "ReDo_from_memory"
    )
    
    //println(msg.agents.foreach(agent => println(agent.neighbors.mkString(s"${agent.name} Array(", ", ", ")"))))
    //monitor ! msg
    
    
    val vax = simulateFromCustomExample(
        Array(1.0f, 0.9f, 0.8f, 0.2f, 0.1f, 0.0f),
        Array(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f),
        Array(
            Array(("Agent2", 0.6f), ("Agent6", 1.0f)),
            Array(("Agent1", 0.6f)),
            Array(("Agent1", 0.4f), ("Agent4", 0.2f)),
            Array(("Agent2", 0.4f), ("Agent3", 0.2f)),
            Array(("Agent3", 0.6f)),
            Array(("Agent4", 0.4f), ("Agent5", 0.6f)),
        ),
        MemoryLessMajority,
        1500,
        "Vaccine_example"
    )
    
    //monitor ! vax
    //
    // Minimum memory majority disensus
    //    val vaxxed = simulateFromCustomExample(
    //        beliefs = Array(1.0f, 0.0f, 0.5f),
    //        influences = Array(
    //            Array(("Agent2", 0.45f), ("Agent3", 0.45f)),
    //            Array(("Agent1", 0.45f), ("Agent3", 0.45f)),
    //            Array(("Agent1", 0.4f), ("Agent2", 0.4f)),
    //        ),
    //        agentType = MemoryMajority,
    //        iterationLimit = 1000,
    //        name = "MM_test"
    //    )
    
    // Memory less clique dissensus
    val ex_2 = simulateFromCustomExample(
        beliefs = Array(1.0f, 0.0f, 0.5f),
        influences = Array(
            Array(("Agent2", 0.1f), ("Agent3", 0.1f)),
            Array(("Agent1", 0.1f), ("Agent3", 0.1f)),
            Array(("Agent1", 0.1f), ("Agent2", 0.1f)),
        ),
        agentType = MemoryConfidence,
        iterationLimit = 1000,
        name = "MM_test"
    )
    
    // Memory less clique dissensus convergence to same private value but not same public value
    val ex_3 = simulateFromCustomExample(
        beliefs = Array(1.0f, 1.0f, 0.5f, 0.0f, 0.0f),
        influences = Array(
            Array(("Agent2", 0.1f), ("Agent3", 0.1f), ("Agent4", 0.2f), ("Agent5", 0.2f)),
            Array(("Agent1", 0.1f), ("Agent3", 0.1f), ("Agent4", 0.2f), ("Agent5", 0.2f)),
            Array(("Agent1", 0.1f), ("Agent2", 0.1f), ("Agent4", 0.1f), ("Agent5", 0.1f)),
            Array(("Agent1", 0.2f), ("Agent2", 0.2f), ("Agent3", 0.1f), ("Agent5", 0.1f)),
            Array(("Agent1", 0.2f), ("Agent2", 0.2f), ("Agent3", 0.1f), ("Agent4", 0.1f)),
        ),
        agentType = MemoryConfidence,
        iterationLimit = 1000,
        name = "MM_test"
    )
    
    val m_d_two_convergence = simulateFromCustomExample(
        beliefs = Array(1.0f, 0.9f, 0.1f, 0.0f),
        influences = Array(
            Array(("Agent2", 0.6f), ("Agent3", 0.1f), ("Agent4", 0.1f)),
            Array(("Agent1", 0.4f), ("Agent3", 0.1f), ("Agent4", 0.1f)),
            Array(("Agent1", 0.1f), ("Agent2", 0.1f), ("Agent4", 0.4f)),
            Array(("Agent1", 0.1f), ("Agent2", 0.1f), ("Agent3", 0.6f)),
        ),
        agentType = MemoryMajority,
        iterationLimit = 1000,
        name = "MM_test",
        tolerances = Array(0.05f, 0.05f, 0.05f, 0.05f)
    )
    
    var outer_weak = 0.21f
    var outer_strong = 0.15f
    var inner_weak = 0.4f
    var inner_strong = 0.32f
    val m_d_private_consensus = simulateFromCustomExample(
        beliefs = Array(1.0f, 0.9f, 0.1f, 0.0f),
        influences = Array(
            Array(("Agent2", inner_weak), ("Agent3", outer_weak), ("Agent4", outer_strong)),
            Array(("Agent1", inner_strong), ("Agent3", outer_weak), ("Agent4", outer_strong)),
            Array(("Agent1", outer_strong), ("Agent2", outer_weak), ("Agent4", inner_strong)),
            Array(("Agent1", outer_strong), ("Agent2", outer_weak), ("Agent3", inner_weak)),
        ),
        agentType = MemoryMajority,
        iterationLimit = 1000,
        name = "MM_test",
        tolerances = Array(0.2f, 0.1f, 0.05f, 0.15f)
    )
    
    outer_weak = 0.3f
    outer_strong = 0.4f
    inner_weak = 0.2f
    inner_strong = 0.35f
    val m_d_private_dissensus = simulateFromCustomExample(
        beliefs = Array(1.0f, 0.9f, 0.1f, 0.0f),
        influences = Array(
            Array(("Agent2", inner_weak), ("Agent3", outer_weak), ("Agent4", outer_strong)),
            Array(("Agent1", inner_strong), ("Agent3", outer_weak), ("Agent4", outer_strong)),
            Array(("Agent1", outer_strong), ("Agent2", outer_weak), ("Agent4", inner_strong)),
            Array(("Agent1", outer_strong), ("Agent2", outer_weak), ("Agent3", inner_weak)),
        ),
        agentType = MemoryMajority,
        iterationLimit = 1000,
        name = "m_d_private",
        tolerances = Array(0.05f, 0.05f, 0.05f, 0.05f)
    )
//    monitor ! m_d_private_dissensus
    
    // Multiple echo chambers
    val ex_5 = simulateFromCustomExample(
        beliefs = Array(
            0.1f, 0.25f, 0.0f,
            0.3f, 0.25f, 0.6f,
            0.95f, 0.8f, 1.0f,
            0.5f
        ),
        influences = Array(
            Array(("Agent2", 0.25f), ("Agent3", 0.25f)),
            Array(("Agent1", 0.25f), ("Agent3", 0.25f)),
            Array(("Agent1", 0.25f), ("Agent2", 0.25f), ("Agent10", 0.1f)),
            
            Array(("Agent5", 0.25f), ("Agent6", 0.25f)),
            Array(("Agent4", 0.25f), ("Agent6", 0.25f)),
            Array(("Agent4", 0.25f), ("Agent5", 0.25f), ("Agent10", 0.1f)),
            
            Array(("Agent8", 0.25f), ("Agent9", 0.25f)),
            Array(("Agent7", 0.25f), ("Agent9", 0.25f)),
            Array(("Agent7", 0.25f), ("Agent8", 0.25f), ("Agent10", 0.1f)),
            
            Array(("Agent3", 0.25f), ("Agent6", 0.25f), ("Agent9", 0.25f)),
        ),
        agentType = MemoryLessMajority,
        iterationLimit = 1000,
        name = "MLM_test",
        tolerances = Array(
            0.25f, 0.25f, 0.25f,
            0.25f, 0.25f, 0.25f,
            0.25f, 0.25f, 0.25f,
            0.05f
        )
    )
    
    val pizza_topping = simulateFromCustomExample(
        beliefs = Array(1.0f, 0.8f, 0.5f, 0.2f, 0.0f),
        influences = Array(
            Array(("Agent2", 0.4f), ("Agent3", 0.1f)),
            Array(("Agent1", 0.4f), ("Agent3", 0.1f)),
            Array(("Agent1", 0.2f), ("Agent2", 0.2f), ("Agent4", 0.2f), ("Agent5", 0.2f)),
            Array(("Agent3", 0.1f), ("Agent5", 0.4f)),
            Array(("Agent3", 0.1f), ("Agent4", 0.4f)),
        ),
        agentType = MemoryLessMajority,
        iterationLimit = 1000,
        name = "pizza_topping",
        tolerances = Array(
            0.2f, 0.15f,
            0.1f,
            0.15f, 0.2f
        )
    )
    //    monitor ! pizza_topping
    
    // Multiple echo chambers
    val sop_example = simulateFromCustomExample(
        beliefs = Array(
            0.1f, 0.2f, 0.15f,
            1.0f, 0.85f,
            0.25f, 0.3f, 0.05f
        ),
        influences = Array(
            Array(("Agent4", 0.2f)),
            Array(("Agent4", 0.2f)),
            Array(("Agent4", 0.2f)),
            
            Array(("Agent1", 0.05f), ("Agent2", 0.1f), ("Agent3", 0.1f), ("Agent5", 0.25f)),
            Array(("Agent4", 0.5f), ("Agent6", 0.1f), ("Agent7", 0.1f), ("Agent8", 0.05f)),
            
            Array(("Agent5", 0.2f)),
            Array(("Agent5", 0.2f)),
            Array(("Agent5", 0.2f)),
        
        ),
        agentType = MemoryLessMajority,
        iterationLimit = 1000,
        name = "sop_example",
        tolerances = Array(
            0.1f, 0.05f, 0.1f,
            0.85f, 0.6f,
            0.05f, 0.1f, 0.05f,
        )
    )
//    monitor ! sop_example
    
    val instability_example = simulateFromCustomExample(
        beliefs = Array(1.0f, 0.0f),
        influences = Array(
            Array(("Agent2", 1f)),
            Array(("Agent1", 1f)),
        ),
        agentType = MemoryLessMajority,
        iterationLimit = 1000,
        name = "divergence",
        tolerances = Array(1.0f, 1.0f)
    )
    
    
    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
}

