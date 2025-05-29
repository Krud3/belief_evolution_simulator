package io.websocket

import play.api.libs.json._

// La forma del JSON que envía tu formulario React
case class AgentTypeEntry(strategy: String, effect: String, count: Int)
case class BiasEntry(bias: String, count: Int)

case class RunRequest(
  numNetworks: Int,
  density: Int,
  iterationLimit: Int,
  stopThreshold: Float,
  saveMode: String,
  degreeDistribution: Float,
  agentTypeDistribution: Seq[AgentTypeEntry],
  cognitiveBiasDistribution: Seq[BiasEntry]
)

object SimulationProtocol {
  implicit val agentTypeEntryReads: Reads[AgentTypeEntry] = Json.reads
  implicit val biasEntryReads:      Reads[BiasEntry]     = Json.reads
  implicit val runRequestReads:     Reads[RunRequest]    = Json.reads
}