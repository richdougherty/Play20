package play.core.server.akkahttp

import akka.actor.ActorSystem
import akka.http.Http
import akka.http.model
import akka.http.model.HttpMethods.GET
import akka.http.model.{HttpHeader, HttpRequest, HttpResponse, HttpEntity, MediaTypes, StatusCodes, Uri}
import akka.http.util.Rendering
import akka.io.IO
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import akka.stream.{ FlattenStrategy, MaterializerSettings, FlowMaterializer }
import akka.util.{ ByteString, Timeout }
import com.typesafe.config.{ ConfigFactory, Config }
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.reactivestreams.api._
import play.api._
import play.api.http.{ HeaderNames, MediaType }
import play.api.libs.iteratee.{ Enumerator, Input, Iteratee, Done }
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

class AkkaHttpServer(config: ServerConfig, appProvider: ApplicationProvider) extends Server with ServerWithStop {

  def applicationProvider = appProvider
  def mode = config.mode

  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("AkkaHttpServer", testConf)
  import system.dispatcher

  val materializer = FlowMaterializer(MaterializerSettings())

  implicit val askTimeout: Timeout = 500.millis
  private val bindingFuture = IO(Http) ? Http.Bind(interface = config.address, port = config.port.get)
  bindingFuture foreach { case Http.ServerBinding(localAddress, connectionStream) =>
    // connectionStream: Producer[IncomingConnection]
    Flow(connectionStream).foreach {
      case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
        //requestProducer: Producer[HttpRequest]
        //responseConsumer: Consumer[HttpResponse]
        println("Accepted new connection from " + remoteAddress)
        // val requestHandler: HttpRequest => HttpResponse = {
        //   case _ => index
        //   // case HttpRequest(GET, Uri.Path("/"), _, _, _)      => index
        //   // case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  => HttpResponse(entity = "PONG!")
        //   // case HttpRequest(GET, Uri.Path("/crash"), _, _, _) => sys.error("BOOM!")
        //   // case _: HttpRequest                                => HttpResponse(404, entity = "Unknown resource!")
        // }
        Flow(requestProducer).map { akkaHttpRequest =>
          val tryApp = applicationProvider.get
          val convertedRequestHeader = convertRequest(tryApp, remoteAddress, akkaHttpRequest)
          val (taggedRequestHeader, handler) = getHandler(tryApp, convertedRequestHeader)
          val responseProducer = executeHandler(
            tryApp,
            akkaHttpRequest,
            taggedRequestHeader,
            handler
          )
          responseProducer
        }.flatten(FlattenStrategy.concat).produceTo(materializer, responseConsumer)
    }.consume(materializer)
  }

  // Taken from PlayDefaultUpstreamHandler
  private val requestIDs = new java.util.concurrent.atomic.AtomicLong(0)

  def convertRequest(
    tryApp: Try[Application],
    remoteAddress: InetSocketAddress,
    akkaHttpRequest: HttpRequest): RequestHeader = {
    val hostAddress = remoteAddress.getAddress.getHostAddress
    // Taken from PlayDefaultUpstreamHander
    new RequestHeader {
      val id = requestIDs.incrementAndGet()
      val tags = Map.empty[String, String]
      def uri = akkaHttpRequest.uri.toString
      def path = akkaHttpRequest.uri.path.toString
      def method = akkaHttpRequest.method.name
      def version = akkaHttpRequest.protocol.toString
      def queryString = akkaHttpRequest.uri.query.toMultiMap
      def headers = {
        val pairs: scala.collection.Seq[(String,String)] = akkaHttpRequest.headers.map((rh: HttpHeader) => (rh.name, rh.value))
        new Headers {
          val data: Seq[(String, Seq[String])] = pairs.groupBy(_._1).mapValues(_.map(_._2)).to[Seq]
        }
      }
      def remoteAddress = hostAddress
      def secure = false // FIXME: Stub
      def username = ??? // None
    }
  }

  def getHandler(tryApp: Try[Application], requestHeader: RequestHeader): (RequestHeader, Handler) = {
    val handlerInfo: Either[Future[Result], (RequestHeader, Handler)] = getHandlerFor(tryApp, requestHeader)
    handlerInfo match {
      case Left(futureResult) =>
        (
          requestHeader,
          EssentialAction(_ => Iteratee.flatten(futureResult.map(result => Done(result, Input.Empty))))
        )
      case Right(pair) => pair
    }
  }

  def executeHandler(
    tryApp: Try[Application],
    request: HttpRequest,
    taggedRequestHeader: RequestHeader,
    handler: Handler): Producer[HttpResponse] = handler match {
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

  // def convertResponseStatus(
  // )

  def convertResponseHeaders(
    tryApp: Try[Application],
    playHeaders: Map[String, String]): immutable.Seq[HttpHeader] = {
    playHeaders.flatMap {
      case (HeaderNames.CONTENT_TYPE, _) => Seq.empty // needs to be set in the HttpEntity, not the headers
      case (name, value) => Seq(model.headers.RawHeader(name, value))
    }.to[immutable.Seq]
  } 

  def convertContentLength(
    tryApp: Try[Application],
    playHeaders: Map[String, String]): Option[Long] = {
    playHeaders.get(HeaderNames.CONTENT_LENGTH).map(java.lang.Long.parseLong(_) /* FIXME: Better parsing */)
  }

  def convertContentType(
    tryApp: Try[Application],
    playHeaders: Map[String, String]): model.ContentType = {
    playHeaders.get(HeaderNames.CONTENT_TYPE).flatMap(MediaType.parse.apply).fold(model.ContentTypes.`application/octet-stream`) { playMediaType: MediaType =>
      val charsetString: Option[String] = playMediaType.parameters.find(_._1.equalsIgnoreCase("charset")).flatMap(_._2)
      val mimeTypeString: String = playMediaType.mediaType + "/" + playMediaType.mediaSubType

      // FIXME: Ridiculously slow - probably need to fix akka-http media type API
      val extension: Option[String] = play.api.libs.MimeTypes.types.find(_._2 == mimeTypeString).map(_._1)
      val mediaType: model.MediaType = extension.flatMap(model.MediaTypes.forExtension).getOrElse(???)
      val charset: Option[model.HttpCharset] = charsetString.map {
        case "UTF-8" => model.HttpCharsets.`UTF-8`
        case "utf-8" => model.HttpCharsets.`UTF-8`
        case unknown => sys.error(s"unknown charset: $unknown")
      }
      model.ContentType(mediaType, charset)
    }
  }


  def executeAction(
    tryApp: Try[Application],
    request: HttpRequest,
    taggedRequestHeader: RequestHeader,
    action: EssentialAction): Producer[HttpResponse] = {

    val requestBodyEnumerator: Enumerator[Array[Byte]] = request.entity match {
      case HttpEntity.Strict(_, data) if data.isEmpty => Enumerator.eof
      case HttpEntity.Default(_, 0, _) => Enumerator.eof
      case HttpEntity.Strict(contentType, data) => ???
      case HttpEntity.Default(contentType, contentLength, data) => ???
      case HttpEntity.Chunked(contentType, chunkds) => ???
    }

    val actionIteratee: Iteratee[Array[Byte], Result] = action(taggedRequestHeader)

    val actionResultFuture: Future[Result] = requestBodyEnumerator |>>> actionIteratee

// final class ResponseHeader(val status: Int, _headers: Map[String, String] = Map.empty) {
//   val headers: Map[String, String] = TreeMap[String, String]()(CaseInsensitiveOrdered) ++ _headers



// final case class HttpResponse(status: StatusCode = StatusCodes.OK,
//                               headers: immutable.Seq[HttpHeader] = Nil,
//                               entity: HttpEntity = HttpEntity.Empty,
//                               protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends japi.HttpResponse with HttpMessage {

  // /**
  //  * An HttpEntity that is "well-behaved" according to the HTTP/1.1 spec as that
  //  * it is either chunked or defines a content-length that is known a-priori.
  //  * Close-delimited entities are not `Regular` as they exists primarily for backwards compatibility with HTTP/1.0.
  //  */
  // sealed trait Regular extends japi.HttpEntityRegular with HttpEntity {

  // /**
  //  * The model for the entity of a "regular" unchunked HTTP message with known, fixed data.
  //  * @param contentType
  //  * @param data
  //  */
  // final case class Strict(contentType: ContentType, data: ByteString) extends japi.HttpEntityStrict with Regular {
  //   def isKnownEmpty: Boolean = data.isEmpty

  // /**
  //  * The model for the entity of a "regular" unchunked HTTP message with a known non-zero length.
  //  */
  // final case class Default(contentType: ContentType,
  //                          contentLength: Long,
  //                          data: Producer[ByteString]) extends japi.HttpEntityDefault with Regular {

  // /**
  //  * The model for the entity of an HTTP response that is terminated by the server closing the connection.
  //  * The content-length of such responses is unknown at the time the response headers have been received.
  //  * Note that this type of HttpEntity cannot be used for HttpRequests!
  //  */
  // final case class CloseDelimited(contentType: ContentType, data: Producer[ByteString]) extends japi.HttpEntityCloseDelimited with HttpEntity {

  // /**
  //  * The model for the entity of a chunked HTTP message (with `Transfer-Encoding: chunked`).
  //  */
  // final case class Chunked(contentType: ContentType, chunks: Producer[ChunkStreamPart]) extends japi.HttpEntityChunked with Regular {

    val resultFuture: Future[Result] = requestBodyEnumerator |>>> actionIteratee
    val responseFuture: Future[HttpResponse] = resultFuture.map { result =>
      val entity = {
        val contentLength: Option[Long] = convertContentLength(tryApp, result.header.headers)
        val contentType = convertContentType(tryApp, result.header.headers)
        contentLength match {
          case None =>
            val chunksEnum = (
              result.body.map(HttpEntity.ChunkStreamPart(_)) >>>
              Enumerator.enumInput(Input.El(HttpEntity.LastChunk)) >>>
              Enumerator.eof
            )
            val chunksProd = Streams.enumeratorToProducer(chunksEnum)
            HttpEntity.Chunked(
              contentType = contentType,
              chunks = chunksProd
            )
          case Some(0) =>
            HttpEntity.Strict(
              contentType = contentType,
              data = ByteString.empty
            )
          case Some(l) =>
            val dataEnum: Enumerator[ByteString] = result.body.map(ByteString(_)) >>> Enumerator.eof
            val dataProd: Producer[ByteString] = Streams.enumeratorToProducer(dataEnum)
            // TODO: Check if values already available so we can use HttpEntity.Strict
            HttpEntity.Default(
              contentType = contentType,
              contentLength = l,
              data = dataProd
            )
          }
      }

      HttpResponse(
        status = StatusCodes.OK,
        headers = convertResponseHeaders(tryApp, result.header.headers),
        entity = entity,
        protocol = model.HttpProtocols.`HTTP/1.1`)
    }
    Streams.futureToProducer(responseFuture)
  }

        //responseConsumer: Consumer[HttpResponse]


// // final case class HttpRequest(method: HttpMethod = HttpMethods.GET,
// //                              uri: Uri = Uri./,
// //                              headers: immutable.Seq[HttpHeader] = Nil,
// //                              entity: HttpEntity.Regular = HttpEntity.Empty,
// //                              protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends japi.HttpRequest with HttpMessage {

// // sealed abstract case class Uri(scheme: String, authority: Authority, path: Path, query: Query,
// //                                fragment: Option[String]) {
//     import play.api.mvc.{ Headers, RequestHeader}

//     val tryApp: Try[Application] = applicationProvider.getCurrent

//     val hostAddress = remoteAddress.getAddress.getHostAddress
//     // Taken from PlayDefaultUpstreamHander
//     val untaggedRequestHeader = new RequestHeader {
//       val id = requestIDs.incrementAndGet()
//       val tags = Map.empty[String, String]
//       def uri = akkaHttpRequest.uri.toString
//       def path = akkaHttpRequest.uri.path.toString
//       def method = akkaHttpRequest.method.toString
//       def version = akkaHttpRequest.protocol.toString
//       def queryString = akkaHttpRequest.uri.query.toMultiMap
//       def headers = {
//         val pairs: scala.collection.Seq[(String,String)] = akkaHttpRequest.headers.map((rh: HttpHeader) => (rh.name, rh.value))
//         new Headers {
//           val data: Seq[(String, Seq[String])] = pairs.groupBy(_._1).mapValues(_.map(_._2)).to[Seq]
//         }
//       }
//       def remoteAddress = hostAddress
//       def secure = ???
//       def username = ??? // None
//     }

    // TODO: X_FORWARDED_FOR, X_FORWARDED_PROTO

    //val appTry: Try[Application] = applicationProvider.get // FIXME: could throw exception
    //val global = appTry.map(_.global).getOrElse(DefaultGlobal)

    // TODO: Handle bad requests




    // type ErrorHandler = (RequestHeader, Throwable) => Future[Result]

    // val errorHandler: ErrorHandler = handlerInfo match {
    //   case Left(_) => 
    //   case Right((taggedRequestHeader, handler, application)) => (taggedRequestHeader, Right((handler, application)))
    // }


          //     trustxforwarded <- app.configuration.getBoolean("trustxforwarded").orElse(Some(false))
          // if remoteAddress == "127.0.0.1" || trustxforwarded

    // TODO: cleanup on request completion
    // play.api.Play.maybeApplication.foreach(_.global.onRequestCompletion(requestHeader))

  // lazy val index = HttpResponse(
  //   entity = HttpEntity(MediaTypes.`text/html`,
  //     """<html>
  //       <body>
  //         <h1>Say hello to <i>akka-http-core</i>!</h1>
  //         <p>Defined resources:</p>
  //         <ul>
  //           <li><a href="/ping">/ping</a></li>
  //           <li><a href="/crash">/crash</a></li>
  //         </ul>
  //       </body>
  //     </html>"""))

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

object AkkaHttpServer extends ServerStart {

  val defaultServerProvider = new AkkaHttpServerProvider

}

/**
 * Knows how to create an AkkaHttpServer.
 */
class AkkaHttpServerProvider extends ServerProvider {
  def createServer(config: ServerConfig, appProvider: ApplicationProvider) = new AkkaHttpServer(config, appProvider)
}