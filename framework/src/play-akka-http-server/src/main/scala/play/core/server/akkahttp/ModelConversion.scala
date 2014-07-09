package play.core.server.akkahttp

// import akka.actor.ActorSystem
// import akka.http.Http
import akka.http.model.ContentType
// import akka.http.model.HttpMethods.GET
import akka.http.model._
import akka.http.model.HttpEntity._
import akka.http.model.headers.{ `Content-Type`, `Content-Length`, RawHeader }
import akka.http.model.parser.HeaderParser
// import akka.http.util.Rendering
// import akka.io.IO
// import akka.pattern.ask
// import akka.stream.scaladsl.Flow
// import akka.stream.{ FlattenStrategy, MaterializerSettings, FlowMaterializer }
import akka.util.ByteString
// import com.typesafe.config.{ ConfigFactory, Config }
import java.net.InetSocketAddress
// import java.util.concurrent.Executors
import org.reactivestreams.Publisher
// import play.api._
// import play.api.http.{ HeaderNames, MediaType }
import play.api.http.HeaderNames._
import play.api.libs.iteratee.{ Enumeratee, Enumerator, Input, Iteratee, Done }
import play.api.libs.streams.Streams
import play.api.mvc._
// import play.core._
// import play.core.server._
// import play.server.SSLEngineProvider
import scala.collection.immutable
// import scala.concurrent.Future
// import scala.concurrent.duration._
// import scala.util.{ Failure, Success, Try }
// import scala.util.control.NonFatal

object ModelConversion {

  def convertRequest(
    requestId: Long,
    remoteAddress: InetSocketAddress,
    request: HttpRequest): RequestHeader = {
    val remoteHostAddress = remoteAddress.getAddress.getHostAddress
    // Taken from PlayDefaultUpstreamHander
    new RequestHeader {
      val id = requestId
      // Send a tag so we can find out which server we're using
      // in our tests. We're not sending an equivalent tag for
      // with our Netty server backend because the act of sending
      // a tag might make performance slightly lower.
      val tags = Map("HTTP_SERVER" -> "akka-http")
      def uri = request.uri.toString
      def path = request.uri.path.toString
      def method = request.method.name
      def version = request.protocol.toString
      def queryString = request.uri.query.toMultiMap
      val headers = convertRequestHeaders(request)
      def remoteAddress = remoteHostAddress
      def secure = false // FIXME: Stub
      def username = ??? // None
    }
  }

  def convertRequestHeaders(request: HttpRequest): Headers = {
    val entityHeaders: Seq[HttpHeader] = request.entity match {
      case HttpEntity.Strict(contentType, _) =>
        Seq(`Content-Type`(contentType))
      case HttpEntity.Default(contentType, contentLength, _) =>
        Seq(`Content-Type`(contentType), `Content-Length`(contentLength))
      case HttpEntity.Chunked(contentType, _) =>
        Seq(`Content-Type`(contentType))
    }
    val allHeaders: Seq[HttpHeader] = request.headers ++ entityHeaders
    val pairs: scala.collection.Seq[(String, String)] = allHeaders.map((rh: HttpHeader) => (rh.name, rh.value))
    new Headers {
      val data: Seq[(String, Seq[String])] = pairs.groupBy(_._1).mapValues(_.map(_._2)).to[Seq]
    }
  }

  case class AkkaHttpHeaders(
    misc: immutable.Seq[HttpHeader],
    contentType: Option[ContentType],
    contentLength: Option[Long])

  def convertResponseHeaders(
    playHeaders: Map[String, String]): AkkaHttpHeaders = {
    val rawHeaders = playHeaders.map { case (name, value) => RawHeader(name, value) }
    val convertedHeaders: List[HttpHeader] = HeaderParser.parseHeaders(rawHeaders.to[List]) match {
      case (Nil, headers) => headers
      case (errors, _) => sys.error(s"Error parsing response headers: $errors")
    }
    AkkaHttpHeaders(
      misc = convertedHeaders.filter {
        case _: `Content-Type` => false
        case _: `Content-Length` => false
        case _ => true
      },
      contentType = convertedHeaders.collectFirst {
        case ct: `Content-Type` => ct.contentType
      },
      contentLength = convertedHeaders.collectFirst {
        case cl: `Content-Length` => cl.length
      }
    )
  }

  def convertResult(
    result: Result): HttpResponse = {
    val convertedHeaders: AkkaHttpHeaders = convertResponseHeaders(result.header.headers)
    val entity = {
      import play.api.libs.iteratee.Execution.Implicits.trampoline
      val contentType = convertedHeaders.contentType.getOrElse(ContentTypes.`application/octet-stream`)
      val contentLength: Option[Long] = convertedHeaders.contentLength
      contentLength match {
        case None =>
          val chunksEnum = (
            result.body.map(HttpEntity.ChunkStreamPart(_)) >>>
            Enumerator.enumInput(Input.El(HttpEntity.LastChunk)) >>>
            Enumerator.eof
          )
          val chunksProd = Streams.enumeratorToPublisher(chunksEnum)
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
          val dataProd: Publisher[ByteString] = Streams.enumeratorToPublisher(dataEnum)
          // TODO: Check if values already available so we can use HttpEntity.Strict
          HttpEntity.Default(
            contentType = contentType,
            contentLength = l,
            data = dataProd
          )
      }
    }

    HttpResponse(
      status = result.header.status,
      headers = convertedHeaders.misc,
      entity = entity,
      protocol = HttpProtocols.`HTTP/1.1`) // FIXME
  }

}