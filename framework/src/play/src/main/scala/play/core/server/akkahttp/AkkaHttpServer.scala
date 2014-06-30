package play.core.server.netty

import akka.actor.ActorSystem
import akka.http.model.{HttpRequest, HttpResponse, HttpEntity, MediaTypes, Uri}
import akka.http.model.HttpMethods.GET
import akka.stream.{ MaterializerSettings, FlowMaterializer }
import akka.util.Timeout
import akka.io.IO
import akka.http.Http
import akka.pattern.ask


import scala.concurrent.duration._
import com.typesafe.config.{ ConfigFactory, Config }
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import play.api._
import play.core.server.{ Server, ServerConfig, ServerSSLEngine, ServerWithStop }
import play.core.{ Execution, Invoker, NamedThreadFactory }
import play.server.SSLEngineProvider
import scala.util.control.NonFatal
import akka.stream.scaladsl.Flow

/**
 * creates a Server implementation based Netty
 */
class AkkaHttpServer(config: ServerConfig) extends Server with ServerWithStop {

  require(config.port.isDefined || config.sslPort.isDefined, "Neither http.port nor https.port is specified")

  def applicationProvider = config.appProvider
  def mode = config.mode

  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher

  val materializer = FlowMaterializer(MaterializerSettings())

  implicit val askTimeout: Timeout = 500.millis
  private val bindingFuture = IO(Http) ? Http.Bind(interface = "localhost", port = 8080)
  bindingFuture foreach { case Http.ServerBinding(localAddress, connectionStream) =>
    Flow(connectionStream).foreach {
      case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
        println("Accepted new connection from " + remoteAddress)
        val requestHandler: HttpRequest => HttpResponse = {
          case _ => index
          // case HttpRequest(GET, Uri.Path("/"), _, _, _)      => index
          // case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  => HttpResponse(entity = "PONG!")
          // case HttpRequest(GET, Uri.Path("/crash"), _, _, _) => sys.error("BOOM!")
          // case _: HttpRequest                                => HttpResponse(404, entity = "Unknown resource!")
        }
        Flow(requestProducer).map(requestHandler).produceTo(materializer, responseConsumer)
    }.consume(materializer)
  }

  // println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")

  // Console.readLine()

  ////////////// helpers //////////////

  lazy val index = HttpResponse(
    entity = HttpEntity(MediaTypes.`text/html`,
      """<html>
        <body>
          <h1>Say hello to <i>akka-http-core</i>!</h1>
          <p>Defined resources:</p>
          <ul>
            <li><a href="/ping">/ping</a></li>
            <li><a href="/crash">/crash</a></li>
          </ul>
        </body>
      </html>"""))

  // The HTTP server channel
  // val HTTP = config.port.map { port =>
  //   val bootstrap = newBootstrap
  //   bootstrap.setPipelineFactory(new PlayPipelineFactory)
  //   val channel = bootstrap.bind(new InetSocketAddress(config.address, port))
  //   allChannels.add(channel)
  //   (bootstrap, channel)
  // }

  // // Maybe the HTTPS server channel
  // val HTTPS = config.sslPort.map { port =>
  //   val bootstrap = newBootstrap
  //   bootstrap.setPipelineFactory(new PlayPipelineFactory(secure = true))
  //   val channel = bootstrap.bind(new InetSocketAddress(config.address, port))
  //   allChannels.add(channel)
  //   (bootstrap, channel)
  // }

  mode match {
    case Mode.Test =>
    case _ => {
      // HTTP.foreach { http =>
      //   Play.logger.info("Listening for HTTP on %s".format(http._2.getLocalAddress))
      // }
      // HTTPS.foreach { https =>
      //   Play.logger.info("Listening for HTTPS on port %s".format(https._2.getLocalAddress))
      // }
    }
  }

  override def stop() {

    try {
      Play.stop()
    } catch {
      case NonFatal(e) => Play.logger.error("Error while stopping the application", e)
    }

    try {
      super.stop()
    } catch {
      case NonFatal(e) => Play.logger.error("Error while stopping logger", e)
    }

    mode match {
      case Mode.Test =>
      case _ => Play.logger.info("Stopping server...")
    }

    system.shutdown()

    // First, close all opened sockets
    // allChannels.close().awaitUninterruptibly()

    // Release the HTTP server
    // HTTP.foreach(_._1.releaseExternalResources())

    // Release the HTTPS server if needed
    // HTTPS.foreach(_._1.releaseExternalResources())

    mode match {
      case Mode.Dev =>
        Invoker.lazySystem.close()
        Execution.lazyContext.close()
      case _ => ()
    }
  }

  override lazy val mainAddress = {
    new InetSocketAddress(config.address, config.port.get)
    // if (HTTP.isDefined) {
    //   HTTP.get._2.getLocalAddress.asInstanceOf[InetSocketAddress]
    // } else {
    //   HTTPS.get._2.getLocalAddress.asInstanceOf[InetSocketAddress]
    // }
  }

}