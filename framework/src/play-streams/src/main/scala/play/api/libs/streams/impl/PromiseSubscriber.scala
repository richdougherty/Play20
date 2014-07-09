package play.api.libs.streams.impl

import org.reactivestreams._
import play.api.libs.concurrent.StateMachine
import play.api.libs.iteratee.Execution
import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Promise }
import scala.util.{ Failure, Success, Try }

private[streams] object PromiseSubscriber {
  sealed trait State
  final case object AwaitingSubscription extends State
  final case object Subscribed extends State
  final case object Completed extends State
}

import PromiseSubscriber._

// Assume that promise's onComplete handler runs asynchronously
private[streams] class PromiseSubscriber[T](prom: Promise[T]) extends StateMachine[State](AwaitingSubscription) with Subscriber[T] {

  // Streams methods
  override def onSubscribe(subscription: Subscription): Unit = exclusive {
    case AwaitingSubscription =>
      // Check if promise is completed. Even if we request elements, we
      // still need to handle the Promise completing in some other way.
      if (prom.isCompleted) {
        state = Completed
        subscription.cancel()
      } else {
        state = Subscribed
        subscription.request(1)
      }
    case Subscribed | Completed =>
      subscription.cancel()
  }

  override def onError(cause: Throwable): Unit = exclusive {
    case AwaitingSubscription | Subscribed =>
      state = Completed
      prom.failure(cause) // OK because we assume onComplete is asynchronous
    case Completed =>
      ()
  }

  override def onComplete(): Unit = exclusive {
    case AwaitingSubscription | Subscribed =>
      prom.failure(new IllegalStateException("Can't handle onComplete until an element has been received"))
      state = Completed
    case Completed =>
      ()
  }

  override def onNext(element: T): Unit = exclusive {
    case AwaitingSubscription =>
      state = Completed
      throw new IllegalStateException("Can't handle onNext until at least one subscription has occurred")
    case Subscribed =>
      state = Completed
      prom.success(element)
    case Completed =>
      ()
  }

}
