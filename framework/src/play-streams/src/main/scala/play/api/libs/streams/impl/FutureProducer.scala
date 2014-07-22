package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee.Execution
import scala.annotation.tailrec
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

final class FutureProducer[T](fut: Future[T]) extends AbstractProducer[T,FutureProducerSubscription[T]] {

  override protected def createSubscription(sub: Subscriber[T]) = new FutureProducerSubscription(this, sub, fut)
  override protected def onSubscriptionAdded(subscription: FutureProducerSubscription[T]): Unit = {
    fut.value match {
      case Some(Failure(t)) =>
        subscription.subscriber.onError(t)
        removeSubscription(subscription)
      case _ =>
        subscription.subscriber.onSubscribe(subscription)
    }
  }

}

private[streams] object FutureProducerSubscription {
  sealed trait State
  final case object AwaitingRequest extends State
  final case object Requested extends State
  final case object Completed extends State
  final case object Cancelled extends State
}

class FutureProducerSubscription[T](prod: FutureProducer[T], sub: Subscriber[T], fut: Future[T]) extends CheckableSubscription[T] {
  import FutureProducerSubscription._
  private val state = new AtomicReference[State](AwaitingRequest)

  override def requestMore(elements: Int): Unit = {
    if (elements <= 0) throw new IllegalArgumentException(s"The number of requested elements must be > 0: requested $elements elements")
    @tailrec
    def handleState(): Unit = {
      state.get match {
        case AwaitingRequest =>
          if (state.compareAndSet(AwaitingRequest, Requested)) {
            fut.value match {
              case Some(result) => onFutureCompleted(result)
              case None => fut.onComplete(onFutureCompleted)(Execution.trampoline) // safe because onFutureCompleted only schedules async ops
            }
          } else handleState()
        case _ =>
          ()
      }
    }
    handleState()
  }

  override def cancel(): Unit = {
    @tailrec
    def handleState(): Unit = {
      state.get match {
        case AwaitingRequest =>
          if (state.compareAndSet(AwaitingRequest, Cancelled)) () else handleState()
        case Requested =>
          if (state.compareAndSet(Requested, Cancelled)) () else handleState()
        case _ =>
          ()
      }
    }
    handleState()
  }

  override def isActive: Boolean = {
    state.get match {
      case AwaitingRequest | Requested => true
      case Cancelled | Completed => false
    }
  }

  override def subscriber: Subscriber[T] = sub

  private def onFutureCompleted(result: Try[T]): Unit = {
    @tailrec
    def handleState(): Unit = {
      state.get match {
        case AwaitingRequest =>
          throw new IllegalStateException("onFutureCompleted shouldn't be called when in state AwaitingRequest")
        case Requested =>
          if (state.compareAndSet(Requested, Completed)) {
            result match {
              case Success(null) =>
                sub.onError(new NullPointerException("Future completed with a null value that cannot be sent by a Producer"))
              case Success(value) =>
                sub.onNext(value)
                sub.onComplete()
              case Failure(t) =>
                sub.onError(t)
            }
            prod.removeSubscription(this)
          } else handleState()
        case Cancelled =>
          ()
        case Completed =>
          throw new IllegalStateException("onFutureCompleted shouldn't be called when already in state Completed")
      }
    }
    handleState()
  }
}