package play.api.libs.streams.impl

import org.reactivestreams._
import play.api.libs.concurrent.StateMachine
import play.api.libs.iteratee.Execution
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

private[streams] final class FuturePublisher[T](fut: Future[T]) extends AbstractPublisher[T, FuturePublisherSubscription[T]] {

  override protected def createSubscription(subr: Subscriber[T]) = new FuturePublisherSubscription(this, subr, fut)
  override protected def onSubscriptionAdded(subscription: FuturePublisherSubscription[T]): Unit = {
    fut.value match {
      case Some(Failure(t)) =>
        subscription.subscriber.onError(t)
        removeSubscription(subscription)
      case _ =>
        subscription.subscriber.onSubscribe(subscription)
    }
  }

}

private[streams] object FuturePublisherSubscription {
  sealed trait State
  final case object AwaitingRequest extends State
  final case object Requested extends State
  final case object Completed extends State
  final case object Cancelled extends State
}

import FuturePublisherSubscription._

private[streams] class FuturePublisherSubscription[T](pubr: FuturePublisher[T], subr: Subscriber[T], fut: Future[T])
    extends StateMachine[State](AwaitingRequest) with CheckableSubscription[T] {

  override def request(elements: Int): Unit = {
    if (elements <= 0) throw new IllegalArgumentException(s"The number of requested elements must be > 0: requested $elements elements")
    exclusive {
      case AwaitingRequest =>
        state = Requested
        fut.value match {
          case Some(result) => onFutureCompleted(result)
          case None => fut.onComplete(onFutureCompleted)(Execution.trampoline) // safe because onFutureCompleted only schedules async ops
        }
      case _ =>
        ()
    }
  }

  override def cancel(): Unit = exclusive {
    case AwaitingRequest =>
      state = Cancelled
    case Requested =>
      state = Cancelled
    case _ =>
      ()
  }

  override def isActive: Boolean = state match {
    case AwaitingRequest | Requested => true
    case Cancelled | Completed => false
  }

  override def subscriber: Subscriber[T] = subr

  private def onFutureCompleted(result: Try[T]): Unit = exclusive {
    case AwaitingRequest =>
      throw new IllegalStateException("onFutureCompleted shouldn't be called when in state AwaitingRequest")
    case Requested =>
      state = Completed
      result match {
        case Success(null) =>
          subr.onError(new NullPointerException("Future completed with a null value that cannot be sent by a Publisher"))
        case Success(value) =>
          subr.onNext(value)
          subr.onComplete()
        case Failure(t) =>
          subr.onError(t)
      }
      pubr.removeSubscription(this)
    case Cancelled =>
      ()
    case Completed =>
      throw new IllegalStateException("onFutureCompleted shouldn't be called when already in state Completed")
  }
}