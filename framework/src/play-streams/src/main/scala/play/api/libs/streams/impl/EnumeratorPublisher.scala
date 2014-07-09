package play.api.libs.streams.impl

import org.reactivestreams._
import play.api.libs.concurrent.StateMachine
import play.api.libs.iteratee._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }

private[streams] final class EnumeratorPublisher[T](
    val enum: Enumerator[T],
    val emptyElement: Option[T] = None) extends AbstractPublisher[T, EnumeratorPublisherSubscription[T]] {

  override protected def createSubscription(subr: Subscriber[T]) = new EnumeratorPublisherSubscription(this, subr)
  override protected def onSubscriptionAdded(subscription: EnumeratorPublisherSubscription[T]): Unit = {
    subscription.subscriber.onSubscribe(subscription)
  }

}

private[streams] object EnumeratorPublisherSubscription {

  sealed trait IterateeState[+T]
  final case object Unattached extends IterateeState[Nothing]
  final case class Attached[T](link: Promise[Iteratee[T, Unit]]) extends IterateeState[T]

  sealed trait State[+T]
  final case class Requested[T](n: Int, attached: IterateeState[T]) extends State[T]
  final case object Completed extends State[Nothing]
  final case object Cancelled extends State[Nothing]
}

import EnumeratorPublisherSubscription._

private[streams] class EnumeratorPublisherSubscription[T](pubr: EnumeratorPublisher[T], subr: Subscriber[T])
    extends StateMachine[State[T]](Requested[T](0, Unattached)) with CheckableSubscription[T] {

  override def subscriber: Subscriber[T] = subr
  override def isActive: Boolean = {
    // run immediately, don't wait for exclusive access
    state match {
      case Requested(_, _) => true
      case Completed | Cancelled => false
    }
  }

  override def request(elements: Int): Unit = {
    if (elements <= 0) throw new IllegalArgumentException(s"The number of requested elements must be > 0: requested $elements elements")
    exclusive {
      case Requested(0, its) =>
        state = Requested(elements, extendIteratee(its))
      case Requested(n, its) =>
        state = Requested(n + elements, its)
      case Completed | Cancelled =>
        () // FIXME: Check rules
    }
  }

  override def cancel(): Unit = exclusive {
    case Requested(_, _) =>
      state = Cancelled
    case Cancelled | Completed =>
      ()
  }

  private def elementEnumerated(el: T): Unit = exclusive {
    case Requested(1, its) =>
      subr.onNext(el)
      state = Requested(0, its)
    case Requested(n, its) =>
      subr.onNext(el)
      state = Requested(n - 1, extendIteratee(its))
    case Cancelled =>
      ()
    case Completed =>
      throw new IllegalStateException("Shouldn't receive another element once completed")
  }

  private def emptyEnumerated(): Unit = exclusive {
    case Requested(n, its) =>
      state = Requested(n, extendIteratee(its))
    case Cancelled =>
      ()
    case Completed =>
      throw new IllegalStateException("Shouldn't receive an empty input once completed")
  }

  private def eofEnumerated(): Unit = exclusive {
    case Requested(_, _) =>
      subr.onComplete()
      state = Completed
    case Cancelled =>
      ()
    case Completed =>
      throw new IllegalStateException("Shouldn't receive EOF once completed")
  }

  private def extendIteratee(its: IterateeState[T]): IterateeState[T] = {
    val link = Promise[Iteratee[T, Unit]]()
    val linkIteratee: Iteratee[T, Unit] = Iteratee.flatten(link.future)
    val iteratee: Iteratee[T, Unit] = Cont { input =>
      input match {
        case Input.El(el) =>
          elementEnumerated(el)
        case Input.Empty =>
          pubr.emptyElement match {
            case None => emptyEnumerated()
            case Some(el) => elementEnumerated(el)
          }
        case Input.EOF =>
          eofEnumerated()
      }
      linkIteratee
    }
    its match {
      case Unattached =>
        pubr.enum(iteratee)
      case Attached(link0) =>
        link0.success(iteratee)
    }
    Attached(link)
  }

}