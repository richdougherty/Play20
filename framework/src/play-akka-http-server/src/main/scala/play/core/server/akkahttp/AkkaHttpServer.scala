package play.core.server.akkahttp

import akka.actor.ActorSystem
import akka.http.Http
import akka.http.model
import akka.http.model.headers.{ `Content-Length`, `Content-Type` }
import akka.http.model.HttpMethods.GET
import akka.http.model.{ ContentType, ContentTypes, HttpHeader, HttpRequest, HttpResponse, HttpEntity, MediaTypes, StatusCodes, Uri }
import akka.http.util.Rendering
import akka.io.IO
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import akka.stream.{ FlattenStrategy, MaterializerSettings, FlowMaterializer }
import akka.util.{ ByteString, Timeout }
import com.typesafe.config.{ ConfigFactory, Config }
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.reactivestreams._
import play.api._
import play.api.http.{ HeaderNames, MediaType }
import play.api.libs.iteratee.{ Enumeratee, Enumerator, Input, Iteratee, Done }
import play.api.libs.streams.Streams
import play.api.mvc._
import play.core._
import play.core.server._
import play.server.SSLEngineProvider
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

/**
 * Starts a Play server using Akka HTTP.
 */
class AkkaHttpServer(config: ServerConfig, appProvider: ApplicationProvider) extends Server with ServerWithStop {

  def applicationProvider = appProvider
  def mode = config.mode

  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("AkkaHttpServer", testConf)
  import system.dispatcher

  implicit val materializer = FlowMaterializer(MaterializerSettings())

  private val bindingFuture = {
    implicit val askTimeout: Timeout = 500.millis
    IO(Http) ? Http.Bind(interface = config.address, port = config.port.get)
  }
  bindingFuture foreach {
    case Http.ServerBinding(localAddress, connectionStream) =>
      Flow(connectionStream).foreach {
        case Http.IncomingConnection(remoteAddress, requestPublisher, responseSubscriber) ⇒
          Flow(requestPublisher)
            .map(handleRequest(remoteAddress, _))
            .flatten(FlattenStrategy.concat)
            .produceTo(responseSubscriber)
      }
  }

  private def handleRequest(remoteAddress: InetSocketAddress, request: HttpRequest): Publisher[HttpResponse] = {
    val tryApp = applicationProvider.get
    val requestId = requestIDs.incrementAndGet()
    val convertedRequestHeader = ModelConversion.convertRequest(
      requestId,
      remoteAddress,
      request)
    val (taggedRequestHeader, handler, newTryApp) = getHandler(tryApp, convertedRequestHeader)
    val responsePublisher = executeHandler(
      newTryApp,
      request,
      taggedRequestHeader,
      handler
    )
    responsePublisher
  }

  // Taken from PlayDefaultUpstreamHandler
  private val requestIDs = new java.util.concurrent.atomic.AtomicLong(0)

  private def getHandler(tryApp: Try[Application], requestHeader: RequestHeader): (RequestHeader, Handler, Try[Application]) = {
    getHandlerFor(requestHeader) match {
      case Left(futureResult) =>
        (
          requestHeader,
          EssentialAction(_ => Iteratee.flatten(futureResult.map(result => Done(result, Input.Empty)))),
          Failure(new Exception("getHandler returned Result, but not Application"))
        )
      case Right((newRequestHeader, handler, newApp)) =>
        (
          newRequestHeader,
          handler,
          Success(newApp) // TODO: Change getHandlerFor to use existing request's app
        )
    }
  }

  private def executeHandler(
    tryApp: Try[Application],
    request: HttpRequest,
    taggedRequestHeader: RequestHeader,
    handler: Handler): Publisher[HttpResponse] = handler match {
    //execute normal action
    case action: EssentialAction =>
      val actionWithErrorHandling = EssentialAction { rh =>
        import play.api.libs.iteratee.Execution.Implicits.trampoline
        Iteratee.flatten(action(rh).unflatten.map(_.it).recover {
          case error =>
            Iteratee.flatten(
              handleHandlerError(tryApp, taggedRequestHeader, error).map(result => Done(result, Input.Empty))
            ): Iteratee[Array[Byte], Result]
        })
      }
      executeAction(tryApp, request, taggedRequestHeader, actionWithErrorHandling)
    case _ => ???
  }

  private def globalSettingsForApp(tryApp: Try[Application]): GlobalSettings = {
    tryApp.map(_.global).getOrElse(DefaultGlobal)
  }

  /** Error handling to use during execution of a handler (e.g. an action) */
  private def handleHandlerError(tryApp: Try[Application], rh: RequestHeader, t: Throwable): Future[Result] = {
    tryApp match {
      case Success(app) => app.handleError(rh, t)
      case Failure(_) => globalSettingsForApp(tryApp).onError(rh, t)
    }
  }

  def executeAction(
    tryApp: Try[Application],
    request: HttpRequest,
    taggedRequestHeader: RequestHeader,
    action: EssentialAction): Publisher[HttpResponse] = {

    val requestBodyEnumerator: Enumerator[Array[Byte]] = request.entity match {
      case HttpEntity.Strict(_, data) if data.isEmpty =>
        Enumerator.eof
      case HttpEntity.Strict(_, data) =>
        Enumerator.apply[Array[Byte]](data.toArray) >>> Enumerator.eof
      case HttpEntity.Default(_, 0, _) =>
        Enumerator.eof
      case HttpEntity.Default(contentType, contentLength, pubr) =>
        // FIXME: should do something with the content-length?
        Streams.publisherToEnumerator(pubr) &> Enumeratee.map((data: ByteString) => data.toArray)
      case HttpEntity.Chunked(contentType, chunks) =>
        // FIXME: Don't enumerate LastChunk?
        // FIXME: do something with trailing headers?
        Streams.publisherToEnumerator(chunks) &> Enumeratee.map((chunk: HttpEntity.ChunkStreamPart) => chunk.data.toArray)
    }

    val actionIteratee: Iteratee[Array[Byte], Result] = action(taggedRequestHeader)
    val resultFuture: Future[Result] = requestBodyEnumerator |>>> actionIteratee
    val responseFuture: Future[HttpResponse] = resultFuture.map(ModelConversion.convertResult)

    Streams.futureToPublisher(responseFuture)
  }

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

    appProvider.get.foreach(Play.stop)

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

object AkkaHttpServer extends ServerStart {

  val defaultServerProvider = new AkkaHttpServerProvider

}

/**
 * Knows how to create an AkkaHttpServer.
 */
class AkkaHttpServerProvider extends ServerProvider {
  def createServer(config: ServerConfig, appProvider: ApplicationProvider) = new AkkaHttpServer(config, appProvider)
}