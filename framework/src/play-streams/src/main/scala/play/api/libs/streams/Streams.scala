package play.api.libs.streams

import org.reactivestreams.api._
import play.api.libs.iteratee.Enumerator
import scala.concurrent.{ ExecutionContext, Future, Promise }

object Streams {
  def futureToProducer[T](fut: Future[T])(implicit ec: ExecutionContext): Producer[T] = new impl.FutureProducer(fut)
  def promiseToConsumer[T](prom: Promise[T])(implicit ec: ExecutionContext): Consumer[T] = new impl.PromiseConsumer(prom, ec.prepare)

  def linkedConsumerAndFuture[T](implicit ec: ExecutionContext): (Consumer[T], Future[T]) = {
    val prom = Promise[T]
    (promiseToConsumer(prom), prom.future)
  }
  def linkedPromiseAndProducer[T](implicit ec: ExecutionContext): (Promise[T], Producer[T]) = {
    val prom = Promise[T]
    (prom, futureToProducer(prom.future))
  }
  def enumeratorToProducer[T](enum: Enumerator[T], emptyElement: Option[T] = None) = new impl.EnumeratorProducer(enum, emptyElement)
}
