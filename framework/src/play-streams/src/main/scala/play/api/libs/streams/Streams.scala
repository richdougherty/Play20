package play.api.libs.streams

import org.reactivestreams.api._
import play.api.libs.iteratee._
import scala.concurrent.{ ExecutionContext, Future, Promise }

object Streams {
  def futureToProducer[T](fut: Future[T]): Producer[T] = new impl.FutureProducer(fut)
  //def promiseToConsumer[T](prom: Promise[T])(implicit ec: ExecutionContext): Consumer[T] = new impl.PromiseConsumer(prom, ec.prepare)

  // def linkedConsumerAndFuture[T](implicit ec: ExecutionContext): (Consumer[T], Future[T]) = {
  //   val prom = Promise[T]
  //   (promiseToConsumer(prom), prom.future)
  // }
  // def linkedPromiseAndProducer[T](implicit ec: ExecutionContext): (Promise[T], Producer[T]) = {
  //   val prom = Promise[T]
  //   (prom, futureToProducer(prom.future))
  // }

  def iterateeToConsumer[T,U](iter: Iteratee[T,U]): (Consumer[T],Iteratee[T,U]) = {
    val cons = new impl.IterateeConsumer(iter)
    val resultIter = cons.result
    (cons, resultIter)
  }

  // unlike Iteratee.run, this does not feed EOF
  def iterateeDoneToProducer[T,U](iter: Iteratee[T,U]): Producer[U] = {
    iterateeFoldToProducer[T,U,U](iter, {
      case Step.Done(x, _) => Future.successful(x)
      case notDone: Step[T,U] => Future.failed(new Exception("Can only get value from Done iteratee: $notDone"))
    })(Execution.trampoline)
  }

  def iterateeFoldToProducer[T,U,V](iter: Iteratee[T,U], f: Step[T,U] => Future[V])(implicit ec: ExecutionContext): Producer[V] = {
    val fut: Future[V] = iter.fold(f)(ec.prepare)
    val prod: Producer[V] = futureToProducer(fut)
    prod
  }

  // unlike Iteratee.run, this does not feed EOF
  def iterateeToProcessor[T,U](iter: Iteratee[T,U]): Processor[T, U] = {
    val (cons, resultIter) = iterateeToConsumer(iter)
    val prod = iterateeDoneToProducer(resultIter)
    val proc = join(cons, prod)
    proc
  }

  def enumeratorToProducer[T](enum: Enumerator[T], emptyElement: Option[T] = None): Producer[T] =
    new impl.EnumeratorProducer(enum, emptyElement)
  def producerToEnumerator[T](prod: Producer[T]): Enumerator[T] =
    new impl.ProducerEnumerator(prod)

  def join[T,U](cons: Consumer[T], prod: Producer[U]): Processor[T,U] =
    new impl.ConsumerProducerProcessor(cons, prod)
}
