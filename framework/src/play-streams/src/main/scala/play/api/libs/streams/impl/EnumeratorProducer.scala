package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee._
import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }

final class EnumeratorProducer[T](
  val enum: Enumerator[T],
  val emptyElement: Option[T] = None) extends AbstractProducer[T,EnumeratorProducerSubscription[T]] {

  override protected def createSubscription(sub: Subscriber[T]) = new EnumeratorProducerSubscription(this, sub)
  override protected def onSubscriptionAdded(subscription: EnumeratorProducerSubscription[T]): Unit = {
    subscription.subscriber.onSubscribe(subscription)
  }

}

private[streams] object EnumeratorProducerSubscription {

  sealed trait IterateeState[+T]
  final case object Unattached extends IterateeState[Nothing]
  final case class Attached[T](link: Promise[Iteratee[T,Unit]]) extends IterateeState[T]

  sealed trait State[+T]
  final case class Requested[T](n: Int, attached: IterateeState[T]) extends State[T]
  final case object Completed extends State[Nothing]
  final case object Cancelled extends State[Nothing]
}

class EnumeratorProducerSubscription[T](prod: EnumeratorProducer[T], sub: Subscriber[T]) extends CheckableSubscription[T] {
  import EnumeratorProducerSubscription._

  // Use a RunQueue to implement a lock-free mutex
  private val runQueue = new LightRunQueue()
  private def exclusive(debugMessage: => String)(f: State[T] => Unit) = runQueue.scheduleSimple {
    println(s"$debugMessage [state = $state]")
    f(state)
  }

  @volatile var state: State[T] = Requested[T](0, Unattached)
  //println(s"construction: [state = $state]")

  override def subscriber: Subscriber[T] = sub
  override def isActive: Boolean = {
    // run immediately, don't wait for exclusive access
    state match {
      case Requested(_, _) => true
      case Completed | Cancelled => false
    }
  }

  override def requestMore(elements: Int): Unit = {
    //println(s"requestMore($elements) [state = $state]")
    if (elements <= 0) throw new IllegalArgumentException(s"The number of requested elements must be > 0: requested $elements elements")
    exclusive(s"requestMore($elements)") {
      case Requested(0, its) =>
        state = Requested(elements, extendIteratee(its))
      case Requested(n, its) =>
        state = Requested(n+elements, its)
      case Completed | Cancelled =>
        () // FIXME: Check rules
    }
  }

  override def cancel(): Unit = exclusive("cancel()") {
    case Requested(_, _) =>
      state = Cancelled
    case Cancelled | Completed =>
      ()
  }

  private def elementEnumerated(el: T): Unit = exclusive(s"elementEnumerated($el)") {
    case Requested(1, its) =>
      sub.onNext(el)
      state = Requested(0, its)
    case Requested(n, its) =>
      sub.onNext(el)
      state = Requested(n-1, extendIteratee(its))
    case Cancelled =>
      ()
    case Completed =>
      throw new IllegalStateException("Shouldn't receive another element once completed")
  }

  private def emptyEnumerated(): Unit = exclusive(s"emptyEnumerated()") {
    case Requested(n, its) =>
      state = Requested(n, extendIteratee(its))
    case Cancelled =>
      ()
    case Completed =>
      throw new IllegalStateException("Shouldn't receive an empty input once completed")
  }

  private def eofEnumerated(): Unit = exclusive("eofEnumerated()") {
    case Requested(_, _) =>
      sub.onComplete()
      state = Completed
    case Cancelled =>
      ()
    case Completed =>
      throw new IllegalStateException("Shouldn't receive EOF once completed")
  }

  private def extendIteratee(its: IterateeState[T]): IterateeState[T] = {
    println(s"extendingIteratee($its)")
    val link = Promise[Iteratee[T, Unit]]()
    val linkIteratee: Iteratee[T, Unit] = Iteratee.flatten(link.future)
    val iteratee: Iteratee[T, Unit] = Cont { input =>
      println(s"Iteratee got: $input")
      input match {
        case Input.El(el) =>
          elementEnumerated(el)
        case Input.Empty =>
          prod.emptyElement match {
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
        prod.enum(iteratee)
      case Attached(link0) =>
        println(s"Linking new iteratee to old iteratee $link0")
        link0.success(iteratee)
    }
    Attached(link)
  }

}