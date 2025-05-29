package io.websocket

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.ByteString
import akka.http.scaladsl.model.ws.BinaryMessage
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, MergeHub, BroadcastHub, Sink, Source}
import play.api.libs.json._
import java.nio.ByteBuffer
import core.simulation.actors.AddNetworks
import core.simulation.config._
import utils.rng.distributions.Uniform
import core.model.agent.behavior.silence.{SilenceStrategyType, SilenceEffectType}
import core.model.agent.behavior.bias.CognitiveBiasType
import io.websocket.SimulationProtocol._
import core.model.agent.behavior.bias.CognitiveBiases
import akka.http.scaladsl.model.headers.RawHeader


object WebSocketServer {
  private var messagePublisher: Option[Sink[Message, Any]] = None
  private var monitorOpt: Option[ActorRef] = None
  private var initialized = false
  private var system: Option[ActorSystem] = None

  /**
   * Inicializa el servidor WebSocket para enviar y recibir comandos de simulación.
   * @param actorSystem  El ActorSystem de Akka
   * @param monitor      El actor Monitor que recibirá mensajes AddNetworks
   */
  def initialize(actorSystem: ActorSystem, monitor: ActorRef): Unit = {
    if (initialized) return
    monitorOpt = Some(monitor)
    system = Some(actorSystem)
    implicit val sys: ActorSystem = actorSystem
    implicit val ec = actorSystem.dispatcher
    implicit val mat = Materializer(actorSystem)

    // Hub para broadcasting de mensajes salientes
    val (sinkOut, sourceOut) = MergeHub.source[Message]
      .toMat(BroadcastHub.sink[Message])(Keep.both)
      .run()
    messagePublisher = Some(sinkOut)

    // Sink para procesar mensajes entrantes (solo texto JSON)
    val sinkIn: Sink[Message, Any] = Sink.foreach {
      case TextMessage.Strict(text)       => handleIncoming(text)
      case TextMessage.Streamed(stream)    =>
        stream.runFold("")(_ + _).foreach(handleIncoming)
      case _                                => // ignorar binarios
    }

    // Flow bidireccional combinando sinkIn y sourceOut
    val socketFlow = Flow.fromSinkAndSourceMat(sinkIn, sourceOut)(Keep.right)

    // Definir ruta WebSocket con CORS
    val route: Route = corsHandler(
      path("ws") {
        get {
          handleWebSocketMessages(socketFlow)
        }
      }
    )

    // Bindear servidor en localhost:8080
    Http().newServerAt("localhost", 8080).bind(route)
    initialized = true
    println("WebSocket server initialized with command handling")
  }
      // Send binary data to all connected clients
  def sendBinaryData(buffer: ByteBuffer): Unit = {
    
      // Check if we have the required components
      if (!initialized || messagePublisher.isEmpty || system.isEmpty) {
        
          println("Error: WebSocket server not initialized properly")
          return
      }
      
      // Get the implicit materializer from the system
      implicit val materializer: Materializer = Materializer(system.get)
      
      // Create a copy of the buffer to ensure thread safety
      val bytes = new Array[Byte](buffer.remaining())
      buffer.get(bytes)
      buffer.position(buffer.position() - bytes.length) // Reset position
      
      // Create a binary message from the buffer
      val message = BinaryMessage(ByteString(bytes))
      
      // Send both messages to the sink
      Source.single(message).runWith(messagePublisher.get)
  }

  /**
   * Procesa JSON entrante, lo convierte a RunRequest y envía AddNetworks al Monitor
   */
  private def handleIncoming(text: String): Unit = {
    monitorOpt.foreach { monitor =>
      Json.parse(text).validate[RunRequest] match {
        case JsSuccess(req, _) =>
          // Construir distribución de tipos de agente
          val agentTypes = req.agentTypeDistribution.map { at =>
            val strat = SilenceStrategyType.fromString(at.strategy)
            val eff   = SilenceEffectType.fromString(at.effect)
            (strat, eff, at.count)
          }.toArray

          // Construir distribución de sesgos cognitivos
          val biases = req.cognitiveBiasDistribution.map { cb =>
            val biasType = CognitiveBiases.fromString(cb.bias).getOrElse(CognitiveBiasType.DeGroot)
            (biasType, cb.count.toFloat)
          }.toArray

          // Enviar mensaje al Monitor
          monitor ! AddNetworks(
            agentTypeCount     = agentTypes,
            agentBiases        = biases,
            distribution       = Uniform,
            saveMode           = parseSaveMode(req.saveMode),
            recencyFunction    = None,
            numberOfNetworks   = req.numNetworks,
            density            = req.density,
            iterationLimit     = req.iterationLimit,
            degreeDistribution = req.degreeDistribution,
            stopThreshold      = req.stopThreshold
          )

        case JsError(errors) =>
          println(s"WebSocketServer: Invalid RunRequest JSON: $errors")
      }
    }
  }

  /**
   * Mapea un string a un SaveMode de simulación (mismo comportamiento que CLI.stringToSaveMode)
   */
  private def parseSaveMode(str: String): SaveMode = str.toLowerCase match {
    case "full"               => Full
    case "standard"           => Standard
    case "standardlight" | "standard-light" => StandardLight
    case "roundless"          => Roundless
    case "agentlesstyped" | "agentless-typed" => AgentlessTyped
    case "agentless"          => Agentless
    case "performance"        => Performance
    case "debug"              => Debug
    case s if s.startsWith("roundsampling") || s.startsWith("round-sampling") =>
      s.split(":") match {
        case Array(_, num) => RoundSampling(num.toInt)
        case _             => Debug
      }
    case s if s.endsWith("-neighborless") || s.endsWith("neighborless") =>
      val base = s.replaceAll("-?neighborless$", "")
      val mode = parseSaveMode(base)
      if (mode.allowsNeighborless) SaveMode.withNeighborless(mode) else mode
    case _                     => Debug
  }

  /**
   * Añade cabeceras CORS y maneja OPTIONS
   */
  private def corsHandler(inner: Route): Route = {
    respondWithHeaders(
      RawHeader("Access-Control-Allow-Origin", "*"),
      RawHeader("Access-Control-Allow-Credentials", "true"),
      RawHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With")
    ) {
      options { complete("OK") } ~ inner
    }
  }
}
