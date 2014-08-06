package play.core.server.akkahttp

import akka.util.ByteString
import org.reactivestreams.api._
import play.api.libs.iteratee._
import play.api.libs.streams.Streams
import play.api.mvc._

/** A Play Result that uses a Producer instead of an Enumerator. */
final case class StreamResult(header: ResponseHeader, body: Producer[ByteString],
    connection: HttpConnection.Connection = HttpConnection.KeepAlive)

object StreamResult {
  def fromResult(result: Result): StreamResult = {
    import Execution.Implicits.trampoline
    StreamResult(
      header = result.header,
      body = Streams.enumeratorToProducer(result.body &> Enumeratee.map(ByteString(_))),
      connection = result.connection
    )
  }
}

trait StreamAction extends (RequestHeader => Processor[ByteString, StreamResult]) with Handler

object StreamAction {

  def fromEssentialAction(essentialAction: EssentialAction): StreamAction = new StreamAction {

    def apply(requestHeader: RequestHeader): Processor[ByteString, StreamResult] = {
      import Execution.Implicits.trampoline
      val rawIteratee: Iteratee[Array[Byte], Result] = essentialAction.apply(requestHeader)
      val streamsIteratee: Iteratee[ByteString, StreamResult] =
        Enumeratee.map((_: ByteString).toArray) &>> rawIteratee.map(StreamResult.fromResult)
      val processor: Processor[ByteString, StreamResult] = Streams.iterateeToProcessor(streamsIteratee)
      processor
    }

  }
}


//   // def executeAction(
//   //   tryApp: Try[Application],
//   //   request: HttpRequest,
//   //   taggedRequestHeader: RequestHeader,
//   //   action: EssentialAction): Producer[HttpResponse] = {

//     val requestBodyEnumerator: Enumerator[Array[Byte]] = request.entity match {
//       case HttpEntity.Strict(_, data) if data.isEmpty =>
//         Enumerator.eof
//       case HttpEntity.Strict(_, data) =>
//         Enumerator.apply[Array[Byte]](data.toArray) >>> Enumerator.eof
//       case HttpEntity.Default(_, 0, _) =>
//         Enumerator.eof
//       case HttpEntity.Default(contentType, contentLength, prod) =>
//         // FIXME: should do something with the content-length?
//         Streams.producerToEnumerator(prod) &> Enumeratee.map((data: ByteString) => data.toArray)
//       case HttpEntity.Chunked(contentType, chunks) =>
//         // FIXME: Don't enumerate LastChunk?
//         // FIXME: do something with trailing headers?
//         Streams.producerToEnumerator(chunks) &> Enumeratee.map((chunk: HttpEntity.ChunkStreamPart) => chunk.data.toArray)
//     }

//     val actionIteratee: Iteratee[Array[Byte], Result] = action(taggedRequestHeader)

//     val actionResultFuture: Future[Result] = requestBodyEnumerator |>>> actionIteratee

//     val resultFuture: Future[Result] = requestBodyEnumerator |>>> actionIteratee
//     val responseFuture: Future[HttpResponse] = resultFuture.map { result =>
//       val entity = {
//         val contentLength: Option[Long] = convertContentLength(tryApp, result.header.headers)
//         val contentType = convertContentType(tryApp, result.header.headers)
//         contentLength match {
//           case None =>
//             val chunksEnum = (
//               result.body.map(HttpEntity.ChunkStreamPart(_)) >>>
//               Enumerator.enumInput(Input.El(HttpEntity.LastChunk)) >>>
//               Enumerator.eof
//             )
//             val chunksProd = Streams.enumeratorToProducer(chunksEnum)
//             HttpEntity.Chunked(
//               contentType = contentType,
//               chunks = chunksProd
//             )
//           case Some(0) =>
//             HttpEntity.Strict(
//               contentType = contentType,
//               data = ByteString.empty
//             )
//           case Some(l) =>
//             val dataEnum: Enumerator[ByteString] = result.body.map(ByteString(_)) >>> Enumerator.eof
//             val dataProd: Producer[ByteString] = Streams.enumeratorToProducer(dataEnum)
//             // TODO: Check if values already available so we can use HttpEntity.Strict
//             HttpEntity.Default(
//               contentType = contentType,
//               contentLength = l,
//               data = dataProd
//             )
//           }
//       }

//       HttpResponse(
//         status = StatusCodes.OK,
//         headers = convertResponseHeaders(tryApp, result.header.headers),
//         entity = entity,
//         protocol = model.HttpProtocols.`HTTP/1.1`)
//     }
//     Streams.futureToProducer(responseFuture)
//   }
//   }
// }