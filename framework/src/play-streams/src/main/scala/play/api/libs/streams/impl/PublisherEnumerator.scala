package play.api.libs.streams.impl

import org.reactivestreams._
import play.api.libs.iteratee._
import scala.concurrent.{ ExecutionContext, Future, Promise }

final class PublisherEnumerator[T](pubr: Publisher[T]) extends Enumerator[T] {
  def apply[A](i: Iteratee[T, A]): Future[Iteratee[T, A]] = {
    val subr = new IterateeSubscriber(i)
    pubr.subscribe(subr)
    Future.successful(subr.result)
  }
}
