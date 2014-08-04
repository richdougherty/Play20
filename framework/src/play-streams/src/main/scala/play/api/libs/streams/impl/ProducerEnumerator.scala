package play.api.libs.streams.impl

import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee._
import scala.concurrent.{ ExecutionContext, Future, Promise }

final class ProducerEnumerator[T](prod: Producer[T]) extends Enumerator[T] {
  def apply[A](i: Iteratee[T, A]): Future[Iteratee[T, A]] = {
    val cons = new IterateeConsumer(i)
    prod.produceTo(cons)
    Future.successful(cons.result)
  }
}
