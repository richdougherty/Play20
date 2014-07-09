package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import org.reactivestreams.api._
import org.reactivestreams.spi._
import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Promise }
import scala.util.{ Failure, Success, Try }

class PromiseConsumer[T](prom: Promise[T], ec: ExecutionContext) extends Consumer[T] with Subscriber[T] {
  import PromiseConsumer._
  @volatile
  private var state: State = AwaitingSubscription

  // Streams API method
  override val getSubscriber: Subscriber[T] = this

  // Streams SPI methods
  override def onSubscribe(subscription: Subscription): Unit = {
    @tailrec
    def handleState(): Unit = {
      state match {
        case AwaitingSubscription =>
          // Check if promise is completed. Even if we request elements, we
          // still need to handle the Promise completing in some other way.
          if (prom.isCompleted) {
            if (stateUpdater.compareAndSet(this, AwaitingSubscription, Completed)) {
              subscription.cancel()
            } else handleState()
          } else {
            if (stateUpdater.compareAndSet(this, AwaitingSubscription, Subscribed)) {
              subscription.requestMore(1)
            } else handleState()
          }
        case Subscribed | Completed  =>
          subscription.cancel()
      }
    }
    handleState()
  }

  private def runAsync(thunk: => Unit): Unit = {
    ec.execute(new Runnable {
      def run() = thunk
    })
  }

  override def onError(cause: Throwable): Unit = {
    @tailrec
    def handleState(): Unit = {
      state match {
        case AwaitingSubscription =>
          throw new IllegalStateException("Can't handle onError until at least one subscription has occurred")
        case Subscribed =>
          if (stateUpdater.compareAndSet(this, Subscribed, Completed)) {
            runAsync {
              prom.tryFailure(cause)
            }
          } else handleState()
        case Completed =>
          ()
      }
    }
    handleState()
  }

  override def onComplete(): Unit = {
    @tailrec
    def handleState(): Unit = {
      state match {
        case AwaitingSubscription =>
          throw new IllegalStateException("Can't handle onComplete until at least one subscription has occurred")
        case Subscribed =>
          if (stateUpdater.compareAndSet(this, Subscribed, AwaitingSubscription)) {
            // Wait for a new subscription
          } else handleState()
        case Completed =>
          ()
      }
    }
    handleState()
  }

  override def onNext(element: T): Unit = {
    @tailrec
    def handleState(): Unit = {
      state match {
        case AwaitingSubscription =>
          throw new IllegalStateException("Can't handle onComplete until at least one subscription has occurred")
        case Subscribed =>
          if (stateUpdater.compareAndSet(this, Subscribed, Completed)) {
            runAsync {
              prom.trySuccess(element)
            }
          } else handleState()
        case Completed =>
          ()
      }
    }
    handleState()    
  }

}

private[streams] object PromiseConsumer {
  val stateUpdater = AtomicReferenceFieldUpdater.newUpdater(classOf[PromiseConsumer[_]], classOf[State], "state")
  sealed trait State
  final case object AwaitingSubscription extends State
  final case object Subscribed extends State
  final case object Completed extends State
}