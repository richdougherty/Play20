package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee.Execution
import scala.annotation.tailrec
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

abstract class AbstractProducer[T,S<:CheckableSubscription[T]] extends Producer[T] with Publisher[T] {

  protected def createSubscription(sub: Subscriber[T]): S
  protected def onSubscriptionAdded(subscription: S): Unit

  private val subscriptions = new AtomicReference[List[S]](Nil)

  // Streams API methods
  final override def getPublisher: Publisher[T] = this
  final override def produceTo(consumer: Consumer[T]): Unit =
    getPublisher.subscribe(consumer.getSubscriber)

  // Streams SPI method
  final override def subscribe(sub: Subscriber[T]): Unit = {
    val subscription = createSubscription(sub)

    @tailrec
    def addSubscription(): Unit = {
      val oldSubscriptions = subscriptions.get
      if (oldSubscriptions.exists(s => (s.subscriber eq sub) && s.isActive)) {
        sub.onError(new IllegalStateException("Subscriber is already subscribed to this Producer"))
      } else {
        val newSubscriptions: List[S] = subscription::oldSubscriptions
        if (subscriptions.compareAndSet(oldSubscriptions, newSubscriptions)) {
          onSubscriptionAdded(subscription)
        } else addSubscription()
      }
    }
    addSubscription()
  }

  @tailrec
  final def removeSubscription(subscription: S): Unit = {
    val oldSubscriptions = subscriptions.get
    val newSubscriptions = oldSubscriptions.filterNot(_.subscriber eq subscription.subscriber)
    if (subscriptions.compareAndSet(oldSubscriptions, newSubscriptions)) () else removeSubscription(subscription)
  }

}

trait CheckableSubscription[T] extends Subscription {
  def subscriber: Subscriber[T]
  def isActive: Boolean
}