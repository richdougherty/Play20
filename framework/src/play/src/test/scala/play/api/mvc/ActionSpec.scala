/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.api.mvc

import java.util.{List => JList}
import java.util.concurrent.{CopyOnWriteArrayList, TimeUnit}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll
import play.api.http.ParserConfiguration
import play.api.libs.streams.Accumulator
import play.core.test.FakeRequest

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class ActionSpec extends Specification with AfterAll {

  implicit val system = ActorSystem("raw-body-parser-spec")
  implicit val materializer = ActorMaterializer()

  def afterAll(): Unit = {
    materializer.shutdown()
    system.terminate()
  }

  val config = ParserConfiguration()
  val parse = PlayBodyParsers()


  def parse(body: ByteString, memoryThreshold: Int = config.maxMemoryBuffer, maxLength: Long = config.maxDiskBuffer)(parser: BodyParser[RawBuffer] = parse.raw(memoryThreshold, maxLength)): Either[Result, RawBuffer] = {
    val request = FakeRequest(method = "GET", "/x")

    Await.result(parser(request).run(Source.single(body)), Duration.Inf)
  }

  "Action" should {
    "run async handling in a prepared ExecutionContext" in {
      val localPrepareCaller = new ThreadLocal[Option[StackTraceElement]]

      //val stackTraces: JList[Seq[StackTraceElement]] = new CopyOnWriteArrayList()
      class PrepareCallerExecutionContext(caller: Option[StackTraceElement]) extends ExecutionContext {
        override def execute(runnable: Runnable): Unit = {
          val oldCaller  = localPrepareCaller.get()
          localPrepareCaller.set(caller)
          try system.dispatcher.execute(runnable) finally localPrepareCaller.set(oldCaller)
        }
        override def reportFailure(cause: Throwable): Unit = system.dispatcher.reportFailure(cause)
        override def prepare(): ExecutionContext = {
          val caller: StackTraceElement = new Exception().getStackTrace()(1)
          new PrepareCallerExecutionContext(Some(caller))
        }
      }

      val simpleParser: BodyParser[String] = BodyParser("simpleParser") { _: RequestHeader =>
        Accumulator.strict[ByteString, Either[Result, String]](
          {
            case None =>
              Future.successful(Right("strict-none"))
            case Some(bs) =>
              Future.successful(Right("strict-some"))
          },
          Sink.ignore.mapMaterializedValue { _ =>
            Future.successful(Right("streamed"))
          }
        )
      }

      val actionBuilder = new ActionBuilderImpl(simpleParser)(new PrepareCallerExecutionContext(None))
      val action: Action[String] = actionBuilder { request: Request[String] =>
        Results.Ok(request.body + " / " + localPrepareCaller.get())
      }
      def runActionAndGetResultBody(accumulateBody: Accumulator[ByteString, Result] => Future[Result]): String = {
        val rh: RequestHeader = FakeRequest() // Make sure we're calling apply(RequestHeader) not apply(Request)
        val accumulator: Accumulator[ByteString, Result] = action(rh)
        val timeout = Duration(30, TimeUnit.SECONDS)
        val result = Await.result(accumulateBody(accumulator), timeout)
        val bodyString = Await.result(result.body.dataStream.runReduce(_ ++ _), timeout).utf8String
        bodyString
      }

      val strictBodyString = runActionAndGetResultBody(_.run(ByteString("foo")))
      strictBodyString must_== "strict-some / null"

      val streamedBody = Source.apply((0 until 10).map(i => ByteString(i.toByte)))
      val streamedBodyString = runActionAndGetResultBody(_.run(streamedBody))
      streamedBodyString must startWith("streamed / Some(play.api.mvc.Action.apply(")
    }
  }
}
