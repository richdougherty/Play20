/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server.akkahttp

import akka.actor.ActorSystem
import akka.http.Http
import akka.http.model._
import akka.http.model.headers.{ `Content-Length`, `Content-Type` }
import akka.io.IO
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import akka.stream.FlowMaterializer
import akka.util.{ ByteString, Timeout }
import com.typesafe.config.{ ConfigFactory, Config }
import java.net.InetSocketAddress
import org.reactivestreams._
import play.api._
import play.api.http.{ HeaderNames, MediaType }
import play.api.libs.iteratee._
import play.api.libs.streams.Streams
import play.api.mvc._
import play.core.server._
import play.core.{ ApplicationProvider, Execution, Invoker }
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

/**
 * Starts a Play server using Akka HTTP.
 */
class AkkaHttpServer(config: ServerConfig, appProvider: ApplicationProvider) extends Server with ServerWithStop {

  assert(config.port.isDefined, "AkkaHttpServer must be given an HTTP port")
  assert(!config.sslPort.isDefined, "AkkaHttpServer cannot handle HTTPS")

  def applicationProvider = appProvider
  def mode = config.mode

  // Remember that some user config may not be available in development mode due to
  // its unusual ClassLoader.
  val userConfig = ConfigFactory.load().getObject("play.akka-http-server").toConfig
  val system: ActorSystem = {
    implicit val system = ActorSystem(userConfig.getString("actor-system"), userConfig)

    // Bind the sockt
    import system.dispatcher
    implicit val materializer = FlowMaterializer()

    val bindingFuture = {
      import java.util.concurrent.TimeUnit.MILLISECONDS
      implicit val askTimeout: Timeout = Timeout(userConfig.getDuration("http-bind-timeout", MILLISECONDS), MILLISECONDS)
      IO(Http) ? Http.Bind(interface = config.address, port = config.port.get)
    }
    bindingFuture foreach {
      case Http.ServerBinding(localAddress, connectionStream) =>
        Flow(connectionStream).foreach {
          case Http.IncomingConnection(remoteAddress, requestPublisher, responseSubscriber) ⇒
            Flow(requestPublisher)
              .mapFuture(handleRequest(remoteAddress, _))
              .produceTo(responseSubscriber)
        }
    }
    system
  }

  // Each request needs an id
  private val requestIDs = new java.util.concurrent.atomic.AtomicLong(0)

  private def handleRequest(remoteAddress: InetSocketAddress, request: HttpRequest): Future[HttpResponse] = {
    val requestId = requestIDs.incrementAndGet()
    val (convertedRequestHeader, requestBodyEnumerator) = ModelConversion.convertRequest(
      requestId,
      remoteAddress,
      request)
    val (taggedRequestHeader, handler, newTryApp) = getHandler(convertedRequestHeader)
    val responseFuture = executeHandler(
      newTryApp,
      request,
      taggedRequestHeader,
      requestBodyEnumerator,
      handler
    )
    responseFuture
  }

  private def getHandler(requestHeader: RequestHeader): (RequestHeader, Handler, Try[Application]) = {
    import play.api.libs.iteratee.Execution.Implicits.trampoline
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
          Success(newApp) // TODO: Change getHandlerFor to use the app that we already had
        )
    }
  }

  private def executeHandler(
    tryApp: Try[Application],
    request: HttpRequest,
    taggedRequestHeader: RequestHeader,
    requestBodyEnumerator: Enumerator[Array[Byte]],
    handler: Handler): Future[HttpResponse] = handler match {
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
      executeAction(tryApp, request, taggedRequestHeader, requestBodyEnumerator, actionWithErrorHandling)
    case unhandled => sys.error(s"AkkaHttpServer doesn't handle Handlers of this type: $unhandled")
  }

  private def globalSettingsForApp(tryApp: Try[Application]): GlobalSettings = {
    tryApp match {
      case Success(app) => app.global
      case Failure(_) => DefaultGlobal
    }
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
    requestBodyEnumerator: Enumerator[Array[Byte]],
    action: EssentialAction): Future[HttpResponse] = {

    import play.api.libs.iteratee.Execution.Implicits.trampoline

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
    responseFuture
  }

  // TODO: Log information about the address we're listening on, like in NettyServer
  mode match {
    case Mode.Test =>
    case _ =>
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

    // TODO: Orderly shutdown
    system.shutdown()

    mode match {
      case Mode.Dev =>
        Invoker.lazySystem.close()
        Execution.lazyContext.close()
      case _ => ()
    }
  }

  override lazy val mainAddress = {
    // TODO: Handle HTTPS here, like in NettyServer
    new InetSocketAddress(config.address, config.port.get)
  }

}

object AkkaHttpServer extends ServerStart {

  /**
   * A ServerProvider for creating an AkkaHttpServer.
   */
  val defaultServerProvider = new AkkaHttpServerProvider

}

/**
 * Knows how to create an AkkaHttpServer.
 */
private[akkahttp] class AkkaHttpServerProvider extends ServerProvider {
  def createServer(config: ServerConfig, appProvider: ApplicationProvider) = new AkkaHttpServer(config, appProvider)
}