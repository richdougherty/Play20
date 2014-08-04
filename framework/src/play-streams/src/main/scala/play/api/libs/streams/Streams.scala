package play.api.libs.streams

import org.reactivestreams.api._
import play.api.libs.iteratee._
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

  def iterateeToConsumer[T,U](iter: Iteratee[T,U]): (Consumer[T],Iteratee[T,U]) = {
    val cons = new impl.IterateeConsumer(iter)
    val resultIter = cons.result
    (cons, resultIter)
  }

  def enumeratorToProducer[T](enum: Enumerator[T], emptyElement: Option[T] = None): Producer[T] =
    new impl.EnumeratorProducer(enum, emptyElement)
  def producerToEnumerator[T](prod: Producer[T]): Enumerator[T] =
    new impl.ProducerEnumerator(prod)
}
