import akka.actor.{Actor, ActorRef, ActorSystem, DeadLetter, Props}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Random, Success}
import scala.concurrent.duration._
import scala.math.{log, E}

// Agent

// Messages
case class AddToNeighborhood(neighbor: ActorRef)

case class RequestOpinion(belief: Double)

case class SendOpinion(opinion: Int, belief: Double, senderAgent: ActorRef) // 0 = silent, 1 = agree, 2 disagree

case class SendAgentCharacteristics(agentData: AgentCharacteristicsItem)

case class ConfidenceUpdated(hasNextIter: Boolean, confidence: Double, opinion: Int, belief: Double)

case class SendNeighbors(network: Vector[ActorRef], influences: Vector[Double])

// Actor
class Agent(stopThreshold: Double, distribution: Distribution, agentDataSaver: ActorRef, networkSaver: ActorRef)
  extends Actor {
    var belief: Double = -1
    val tolRadius: Double = randomBetween(0, 0.5)
    val tolOffset: Double = randomBetween(-tolRadius, tolRadius)
    var beliefExpressionThreshold: Double = -1
    var perceivedOpinionClimate: Double = 0.0
    var confidenceUnbounded: Double = -1
    var confidence: Double = -1
    var neighbors: Vector[ActorRef] = Vector.empty
    var influences: Vector[Double] = Vector.empty
    var hasUpdatedInfluences: Boolean = false
    var firstIter: Boolean = true
    var round = 0
    implicit val timeout: Timeout = Timeout(60.seconds)

    // Experimental zone
    val openMindedness: Int = 20
    var curInteractions: Int = 0
    //

    // Calculate and return the belief and opinion climate
    def calculateOpinionClimate(callback: (Double, Double) => Unit): Unit = {
        val countsArr: Array[Int] = Array.fill(3)(0)

        // Create a sequence of futures for all neighbors
        val futures: Seq[Future[SendOpinion]] = neighbors.map { neighbor =>
            (neighbor ? RequestOpinion(belief)).mapTo[SendOpinion]
        }

        // Wait for all futures to complete
        Future.sequence(futures).onComplete {
            case Success(opinions) =>
                var currentSum = 0.0
                var ownInfluence = 0.0
                curInteractions += 1
                opinions.foreach { opinion =>
                    countsArr(opinion.opinion) += 1
                    val isSilent = opinion.opinion == 0
                    if (influences.nonEmpty) {
                        if (isSilent) ownInfluence += influences(neighbors.indexOf(opinion.senderAgent))
                        else currentSum += opinion.belief * influences(neighbors.indexOf(opinion.senderAgent))
                    }
                }

                currentSum += belief * ownInfluence
                if (curInteractions == openMindedness) curInteractions = 0
                else currentSum = belief

                val climate = countsArr(1) + countsArr(2) match {
                    case 0 => 0.0
                    case _ => (countsArr(1) - countsArr(2)).toDouble / (countsArr(1) + countsArr(2))
                }
                callback(climate, currentSum)
            case Failure(exception) =>
                println(exception)
        }
    }

    def isCongruent(neighborBelief: Double): Boolean = {
        val lower = belief - tolRadius + tolOffset
        val upper = belief + tolRadius + tolOffset
        lower <= neighborBelief && neighborBelief <= upper
    }

    def receive: Receive = {
        case AddToNeighborhood(neighbor) =>
            neighbors = neighbors :+ neighbor

        case RequestOpinion(senderBelief) =>
            val agentSender = sender()
            if (confidence < beliefExpressionThreshold) agentSender ! SendOpinion(0, belief, self)
            else if (isCongruent(senderBelief)) agentSender ! SendOpinion(1, belief, self)
            else agentSender ! SendOpinion(2, belief, self)

        case UpdateConfidence =>
            val network = sender()
            val oldConfidence = confidence

            // Check if influences have been updated
            // Currently just places random numbers as the influences maybe should use influence counts to
            if (!hasUpdatedInfluences) {
                val random = new Random
                val randomNumbers = Vector.fill(neighbors.size)(random.nextDouble())
                val sum = randomNumbers.sum
                influences = randomNumbers.map(_ / sum)
                hasUpdatedInfluences = true
            }

            calculateOpinionClimate { (climate, updatedBelief) =>
              // Save the initial state aka round 0
                if (round == 0) {
                    agentDataSaver ! SendAgentData(
                        round, self.path.name, belief, confidence, perceivedOpinionClimate,
                        confidence >= beliefExpressionThreshold
                    )
                    println(s"${self.path.name}: neighbors ${neighbors.size}")
                }
              
                round += 1
                perceivedOpinionClimate = climate
                confidenceUnbounded = math.max(confidenceUnbounded + perceivedOpinionClimate, 0)
                confidence = (2 / (1 + Math.exp(-confidenceUnbounded))) - 1
//                println(s"Influences of ${self.path.name}: ${neighbors.size} " +
//                    s"${influences.map(influence => roundToNDecimals(influence, 4))} " +
//                    s"by ${neighbors.map(actor => actor.path.name)}")
                if (!firstIter) belief = updatedBelief
                else {
                    agentDataSaver ! SendStaticAgentData(
                        self.path.name, neighbors.size, tolRadius, tolOffset, beliefExpressionThreshold, openMindedness
                    )
                    networkSaver ! SendNeighbors(neighbors, influences)
                }

                val aboveThreshold = math.abs(confidence - oldConfidence) >= stopThreshold || firstIter
                firstIter = false
                agentDataSaver ! SendAgentData(
                    round, self.path.name, belief, confidence, perceivedOpinionClimate,
                    confidence >= beliefExpressionThreshold
                )

                if (confidence >= beliefExpressionThreshold & belief < 0.5)
                    network ! ConfidenceUpdated(aboveThreshold, confidence, 0, belief)
                else if (confidence >= beliefExpressionThreshold & belief >= 0.5)
                    network ! ConfidenceUpdated(aboveThreshold, confidence, 1, belief)
                else if (confidence < beliefExpressionThreshold & belief < 0.5)
                    network ! ConfidenceUpdated(aboveThreshold, confidence, 2, belief)
                else if (confidence < beliefExpressionThreshold & belief >= 0.5)
                    network ! ConfidenceUpdated(aboveThreshold, confidence, 3, belief)
                else
                    println("Algo raro pasa mate")
            }

        case RequestAgentCharacteristics =>
            val network = sender()
            calculateOpinionClimate { (climate, _) =>
                perceivedOpinionClimate = climate
                val agentData = AgentCharacteristicsItem(
                    neighbors.size,
                    belief,
                    beliefExpressionThreshold,
                    confidence,
                    confidence >= beliefExpressionThreshold,
                    perceivedOpinionClimate
                )
                curInteractions = 0
                network ! SendAgentCharacteristics(agentData)
            }

    }

    override def preStart(): Unit = {
        distribution match {
            case Uniform =>
                // Uniform
                // belief = randomBetween(0.0, 1)
                // Bimodal
                val bimodal = new BimodalDistribution(0.25, 0.75)
                belief = bimodal.sample()

                def reverseConfidence(c: Double): Double = {
                    if (c == 1.0) {
                        37.42994775023705
                    } else {
                        -math.log(-((c - 1) / (c + 1)))
                    }
                }

                beliefExpressionThreshold = Random.nextDouble()
                //confidence = Random.nextDouble()
                //confidenceUnbounded = reverseConfidence(confidence)
                confidenceUnbounded = Random.nextDouble()
                confidence = (2 / (1 + Math.exp(-confidenceUnbounded))) - 1

            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution

            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
        }

    }
}
