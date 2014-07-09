package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee.Execution
import scala.annotation.tailrec
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

final class FutureProducer[T](fut: Future[T]) extends Producer[T] with Publisher[T] {

  private val subscriptions = new AtomicReference[List[FutureProducerSubscription[T]]](Nil)

  // Streams API methods
  override def getPublisher: Publisher[T] = this
  override def produceTo(consumer: Consumer[T]): Unit =
    getPublisher.subscribe(consumer.getSubscriber)

  // Streams SPI method
  override def subscribe(sub: Subscriber[T]): Unit = {
    val subscription = new FutureProducerSubscription(this, sub, fut)

    @tailrec
    def addSubscription(): Boolean = {
      val oldSubscriptions = subscriptions.get
      if (oldSubscriptions.exists(s => (s.sub eq sub) && s.isActive)) {
        sub.onError(new IllegalStateException("Subscriber is already subscribed to this Producer"))
        false
      } else {
        val newSubscriptions: List[FutureProducerSubscription[T]] = subscription::oldSubscriptions
        if (subscriptions.compareAndSet(oldSubscriptions, newSubscriptions)) true else addSubscription()
      }
    }

    val subscribed = addSubscription()
    if (subscribed) {
      fut.value match {
        case Some(Failure(t)) =>
          sub.onError(t)
          removeSubscription(subscription)
        case _ =>
          sub.onSubscribe(subscription)
      }
    }
  }

  @tailrec
  def addSubscriptionUnlessActivelySubscribed(subscription: FutureProducerSubscription[T]): Boolean = {
    val oldSubscriptions = subscriptions.get
    if (oldSubscriptions.exists(s => (s.sub eq subscription.sub) && s.isActive)) {
      false 
    } else {
      val newSubscriptions: List[FutureProducerSubscription[T]] = subscription::oldSubscriptions
      if (subscriptions.compareAndSet(oldSubscriptions, newSubscriptions)) true else addSubscriptionUnlessActivelySubscribed(subscription)
    }
  }

  @tailrec
  def removeSubscription(subscription: FutureProducerSubscription[T]): Unit = {
    val oldSubscriptions = subscriptions.get
    val newSubscriptions = oldSubscriptions.filterNot(_.sub eq subscription.sub)
    if (subscriptions.compareAndSet(oldSubscriptions, newSubscriptions)) () else removeSubscription(subscription)
  }

}

private[streams] object FutureProducerSubscription {
  sealed trait State
  final case object AwaitingRequest extends State
  final case object Requested extends State
  final case object Completed extends State
  final case object Cancelled extends State
}

class FutureProducerSubscription[T](prod: FutureProducer[T], val sub: Subscriber[T], fut: Future[T]) extends Subscription {
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

  def isActive: Boolean = {
    state.get match {
      case AwaitingRequest | Requested => true
      case Cancelled | Completed => false
    }
  }

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