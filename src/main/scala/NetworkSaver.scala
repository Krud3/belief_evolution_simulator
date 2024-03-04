import akka.actor.{Actor, ActorRef, Props}


case class NetworkStructure
(
  source: String,
  target: String,
  value: Double
)

class NetworkSaver(dataSavingPath: String) extends Actor{
  var network = Vector.empty[NetworkStructure]
  var networksSaved = 0

  def receive: Receive = {
    case SendNeighbors(neighbors, influences) =>
      val senderName = sender().path.name
      neighbors.zip(influences).foreach {
        case (neighbor, influence) =>
          network = network :+ NetworkStructure(senderName, neighbor.path.name, influence)
      }
      networksSaved += 1
      //println(s"$senderName saved ${neighbors.map(_.path.name).mkString(", ")}")
    case ExportNetwork(networkName) =>
      val path = s"${dataSavingPath}/network_structure_${networkName}.csv"
      saveToCsv(
        path,
        "source,target,value",
        network,
        d => s"${d.source},${d.target},${d.value}"
      )
  }
}
