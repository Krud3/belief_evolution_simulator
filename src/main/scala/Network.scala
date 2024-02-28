import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Random, Success}
import scala.collection.mutable
// Network

// Messages
case class AddAgent(remainingAgentsToAdd: Int)

case object UpdateConfidence

case class InitialReport(reportData: InitialReportData)

case class RoundReport(reportData: RoundReportData)

case class FinalReport(reportData: FinalReportData)

case object RequestAgentCharacteristics

// Actor
class Network(minNumberOfNeighbors: Int, degreeDistributionParameter: Double, stopThreshold: Double,
              distribution: Distribution, monitor: ActorRef, dataSavingPath: String, dataSaver: ActorRef) extends Actor {
    // Actor: (number of neighbors, attractiveness score, neighbors: , mutable.Seq[Int])
    var agents: mutable.Map[ActorRef, (Int, Double)] = mutable.Map.empty
    var currentRound: Int = 0
    var pendingResponses: Int = 0
    var shouldContinue: Boolean = false
    var roundData: Array[Double] = Array.fill(8)(0)
    var roundAgentData: Array[(Int, Double, Double)] = Array.empty
    var totalAttractiveness: Double = 0.0 // Storing the total attractiveness
    val networkSaver: ActorRef = context.actorOf(Props(new NetworkSaver(dataSavingPath)))
    val agentData: ActorRef = context.actorOf(Props(new AgentDataSaver(dataSavingPath, dataSaver, networkSaver)))
    var fenwickTree = new FenwickTree(0, 0, 0)

    implicit val timeout: Timeout = Timeout(60.seconds)

    def receive: Receive = empty

    def empty: Receive = {
        case BuildNetwork(numberOfAgents) =>
            roundAgentData = Array.fill(numberOfAgents)((0, 0.0, 0.0))
            fenwickTree = new FenwickTree(numberOfAgents, minNumberOfNeighbors, degreeDistributionParameter - 2)
            context.become(building)
            //println("Building network...")
            self ! AddAgent(numberOfAgents)
    }

    def building: Receive = {
        case AddAgent(remainingAgentsToAdd) if remainingAgentsToAdd > 0 =>
            val newAgent = context.actorOf(
                Props(new Agent(stopThreshold, distribution, agentData, networkSaver)),
                s"Agent$remainingAgentsToAdd"
            )
            agents += (newAgent -> (0, minNumberOfNeighbors * (degreeDistributionParameter - 2)))
            
            
            def pickAgentBasedOnAttractiveness(excludedAgents: Set[ActorRef] = Set(newAgent)): ActorRef = {
                val filteredAgents = agents.filterNot { case (agent, _) => excludedAgents.contains(agent) }

                if (filteredAgents.isEmpty) {
                    if (agents.isEmpty) {
                        throw new RuntimeException("No agents available to pick from!")
                    } else {
                        return agents.keys.toSeq(Random.nextInt(agents.size))
                    }
                }

                val totalFilteredAttractiveness = filteredAgents.values.map(_._2).sum
                val target = Random.nextDouble() * totalFilteredAttractiveness
                var accumulated = 0.0
                for ((agent, (qi, attractiveness)) <- filteredAgents) {
                    accumulated += attractiveness
                    if (accumulated >= target) return agent
                }
                filteredAgents.keys.head
            }

            var chosenNeighbors = Set.empty[ActorRef]
            for (_ <- 1 to minNumberOfNeighbors) {
                val newNeighbor = pickAgentBasedOnAttractiveness(chosenNeighbors)
                if (newNeighbor != newAgent) {
                    chosenNeighbors += newNeighbor
                }
            }

            chosenNeighbors.foreach { neighbor =>
                newAgent ! AddToNeighborhood(neighbor)
                neighbor ! AddToNeighborhood(newAgent)
                // Update the agents map and total attractiveness
                val (oldQi, oldAttractiveness) = agents(neighbor)
                val newQi = oldQi + 1
                val newAttractiveness = minNumberOfNeighbors * (degreeDistributionParameter - 2) + newQi
                agents = agents.updated(neighbor, (newQi, newAttractiveness))
                totalAttractiveness += (newAttractiveness - oldAttractiveness)
            }
            agents = agents.updated(newAgent, (
              chosenNeighbors.size,
              (minNumberOfNeighbors * (degreeDistributionParameter - 2)) + chosenNeighbors.size)
            )
            self ! AddAgent(remainingAgentsToAdd - 1)


        case AddAgent(remainingAgentsToAdd) if remainingAgentsToAdd <= 0 =>
            //println("Finished building network")

            //println("Creating initial report")

            // Request characteristics from all agents and collect their responses
            val futures = agents.keys.map { agent =>
                (agent ? RequestAgentCharacteristics).mapTo[SendAgentCharacteristics]
            }

            val collectedFutures = Future.sequence(futures)

            collectedFutures.onComplete {
                case Success(agentCharacteristicsList) =>
                    val agentCharacteristicsVector = agentCharacteristicsList.map(_.agentData).toVector
                    val initialReport = InitialReportData(
                        AgentCharacteristics = agentCharacteristicsVector,
                        density = minNumberOfNeighbors,
                        degreeDistributionParameter = degreeDistributionParameter,
                        stopThreshHold = stopThreshold,
                        distribution = distribution
                    )
                    //println("Finished creating initial report network ready for iteration")
                    context.become(idle)
                    monitor ! InitialReport(initialReport)

                case Failure(e) =>
                // Handle the failure here, e.g., logging the error
            }
    }

    def idle: Receive = {
        case StartNetwork =>
            pendingResponses = agents.size
            shouldContinue = false
            agents.keys.foreach { agent =>
                agent ! UpdateConfidence
            }

        case ConfidenceUpdated(hasNextIter, confidence, opinion, belief) =>
            roundAgentData(pendingResponses - 1) = (
                sender().path.name.stripPrefix("Agent").toInt,
                roundToNDecimals(belief, 4),
                roundToNDecimals(confidence, 4)
            )
            pendingResponses -= 1
            if (hasNextIter) {
                shouldContinue = true
            }


            roundData(opinion) += confidence
            roundData(opinion + 4) += 1

            if (pendingResponses == 0) {
//                val averageBelief = roundAgentData.map(_._2).sum / roundAgentData.length
//                val averageConfidence = roundAgentData.map(_._3).sum / roundAgentData.length
//
//                val sortedRoundAgentData = roundAgentData.sortBy(_._1)
//                val groupedBeliefs = sortedRoundAgentData.grouped(25).map {
//                    group => group.map(_._2).mkString(", ")
//                }.mkString(",\n")
//                val groupedConfidences = sortedRoundAgentData.grouped(25).map {
//                    group => group.map(_._3).mkString(", ")
//                }.mkString(",\n")
//
//                println(s"\nRound${currentRound}:")
//                println(f"Average Belief: $averageBelief%.4f")
//                println(s"($groupedBeliefs)")
//                println(f"Average Confidence: $averageConfidence%.4f")
//                println(s"($groupedConfidences)")
                val roundDataSend = RoundReportData(
                    currentRound,
                    roundData(0),
                    roundData(1),
                    roundData(2),
                    roundData(3),
                    roundData(4).toInt,
                    roundData(5).toInt,
                    roundData(6).toInt,
                    roundData(7).toInt,
                )

                // Send the round data
                monitor ! RoundReport(roundDataSend)

                // Stop if threshold stop condition is met or more than 2001 iterations have happened
                if (shouldContinue && currentRound < 2001) {
                    // Reset round data
                    roundData = Array.fill(8)(0.0)
                    currentRound += 1
                    self ! StartNetwork
                } else {
                    // ToDo Handle stopping of the network
                    //println("Creating final report...")
                    val futures = agents.keys.map { agent =>
                        (agent ? RequestAgentCharacteristics).mapTo[SendAgentCharacteristics]
                    }

                    agentData ! ExportCSV

                    val collectedFutures = Future.sequence(futures)

                    collectedFutures.onComplete {
                        case Success(agentCharacteristicsList) =>
                            val agentCharacteristicsVector = agentCharacteristicsList.map(_.agentData).toVector
                            val finalReport = FinalReportData(
                                totalSteps = currentRound,
                                AgentCharacteristics = agentCharacteristicsVector
                            )
                            monitor ! FinalReport(finalReport)
                        //println("Finished creating final report shutting down...")
                        //context.become(running)

                        case Failure(e) =>
                        // Handle the failure
                    }


                }
            }

    }

    def running: Receive = {
        case _ =>
    }
}
