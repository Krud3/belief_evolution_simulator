package io.websocket

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.HttpMethods._
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import akka.util.ByteString
import play.api.libs.json.Json
import akka.stream.Materializer

import java.nio.ByteBuffer
import java.util.UUID
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

object WebSocketServer {
    // Will hold the sink that we can send messages to
    private var messagePublisher: Option[Sink[Message, Any]] = None
    private var initialized = false
    private var system: Option[ActorSystem] = None
    
    // CORS handler for cross-origin requests
    private def corsHandler(route: Route): Route = {
        respondWithHeaders(
            `Access-Control-Allow-Origin`.*,
            `Access-Control-Allow-Credentials`(true),
            `Access-Control-Allow-Headers`("Authorization", "Content-Type", "X-Requested-With")
            ) {
            options {
                complete("OK")
            } ~ route
        }
    }
    
    // Initialize the WebSocket server
    def initialize(actorSystem: ActorSystem): Unit = {
        if (initialized) return
        
        // Store the actor system for later use
        system = Some(actorSystem)
        
        implicit val sys: ActorSystem = actorSystem
        implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
        implicit val materializer: Materializer = Materializer(actorSystem)
        
        // Create a hub for broadcasting messages to all connected clients
        val (sink, source) = MergeHub.source[Message]
          .toMat(BroadcastHub.sink[Message])(Keep.both)
          .run()
        
        // Store the sink for later use
        messagePublisher = Some(sink)
        
        // Create a WebSocket flow that will handle our WebSocket connections
        val websocketFlow = Flow.fromSinkAndSourceMat(
            Sink.ignore, // Ignore incoming messages from clients
            source       // Broadcast our messages to all clients
            )(Keep.right)
        
        // Define routes
        val webSocketRoute: Route =
            path("ws") {
                get {
                    handleWebSocketMessages(websocketFlow)
                }
            }
        
        // Static resources route
        val staticResourcesRoute: Route =
            pathEndOrSingleSlash {
                get {
                    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
                                        """
                                          |<html>
                                          |  <body>
                                          |    <h1>WebSocket Server is running</h1>
                                          |    <p>Connect to ws://localhost:8080/ws</p>
                                          |  </body>
                                          |</html>
                        """.stripMargin))
                }
            }
        
        // Combine routes with CORS support
        val route = corsHandler(webSocketRoute ~ staticResourcesRoute)
        
        // Start the server
        val bindingFuture = Http().newServerAt("localhost", 8080).bind(route)
        
        println(s"WebSocket server initialized")
        println(s"WebSocket endpoint at ws://localhost:8080/ws")
        
        initialized = true
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
    
    
}