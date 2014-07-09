package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams._
import play.api.libs.iteratee.Execution
import scala.annotation.tailrec
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

private[streams] abstract class AbstractPublisher[T, S <: CheckableSubscription[T]] extends Publisher[T] {

  protected def createSubscription(subr: Subscriber[T]): S
  protected def onSubscriptionAdded(subscription: S): Unit

  private val subscriptions = new AtomicReference[List[S]](Nil)

  final override def subscribe(subr: Subscriber[T]): Unit = {
    val subscription = createSubscription(subr)

    @tailrec
    def addSubscription(): Unit = {
      val oldSubscriptions = subscriptions.get
      if (oldSubscriptions.exists(s => (s.subscriber eq subr) && s.isActive)) {
        subr.onError(new IllegalStateException("Subscriber is already subscribed to this Publisher"))
      } else {
        val newSubscriptions: List[S] = subscription :: oldSubscriptions
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

private[streams] trait CheckableSubscription[T] extends Subscription {
  def subscriber: Subscriber[T]
  def isActive: Boolean
}