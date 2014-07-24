package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee._
import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }


private[streams] object IterateeConsumer {

  sealed trait SubscriptionState[+T]
  final case object AwaitingSubscription extends SubscriptionState[Nothing]
  final case class Subscribed(subscription: Subscription) extends SubscriptionState[Nothing]
  final case class CompleteOrErrorInputWaiting[T](input: Input[T]) extends SubscriptionState[T]
  final case object CompleteOrError extends SubscriptionState[Nothing]
  final case object Unsubscribed extends SubscriptionState[Nothing]

  sealed trait IterateeState[T,R]
  final case class AwaitingStep[T,R]() extends IterateeState[T,R]
  final case class  AwaitingInput[T,R](k: Input[T] => Iteratee[T,R]) extends IterateeState[T,R]
  final case class  Unneeded[T,R]() extends IterateeState[T,R]
  final case class  DoneOrError[T,R]() extends IterateeState[T,R]
}

class IterateeConsumer[T,R,S](
  iter0: Iteratee[T,R],
  doneStepResult: Step.Done[R,T] => Try[S],
  errorStepResult: Step.Error[T] => Try[S],
  onErrorInputAndResult: Throwable => (Option[Input[T]], Option[Try[S]])) extends Consumer[T] with Subscriber[T] {
  import IterateeConsumer._

  // Use a RunQueue to implement a lock-free mutex
  private val runQueue = new LightRunQueue()
  private def exclusive(f: => Unit) = runQueue.scheduleSimple { f }

  private var iterState: IterateeState[T,R] = AwaitingStep()
  private var subState: SubscriptionState[T] = AwaitingSubscription
  private val resultPromise = Promise[S]()

  //private var state: State[T,R] = Initial

  def result: Future[S] = resultPromise.future

  getNextStepFromIteratee(iter0)

  // Streams API method
  override val getSubscriber: Subscriber[T] = this

  // Streams SPI methods
  override def onSubscribe(subscription: Subscription): Unit = exclusive {
    subState match {
      case AwaitingSubscription =>
        subState = Subscribed(subscription)
      case illegal =>
        throw new IllegalStateException("Consumer has already been subscribed")
    }
    iterState match {
      case AwaitingStep() =>
        () // We'll trigger an action once we know the iteratee's step
      case AwaitingInput(k) =>
        subscription.requestMore(1)
      case Unneeded() =>
        throw new IllegalStateException("Iteratee marked as unneeded before subscription has occurred")
      case DoneOrError() =>
        subscription.cancel()
    }
  }

  private def getNextStepFromIteratee(iter: Iteratee[T,R]): Unit = {
    iter0.pureFold {
      case Step.Cont(k) => onContStep(k)
      case d@Step.Done(_, _) => onDoneOrErrorStep(doneStepResult(d))
      case e@Step.Error(_, _) => onDoneOrErrorStep(errorStepResult(e))
    }(Execution.trampoline)
  }

  private def onContStep(k: Input[T] => Iteratee[T,R]): Unit = exclusive {
    iterState match {
      case AwaitingStep() =>
        subState match {
          case AwaitingSubscription =>
            iterState = AwaitingInput(k)
          case Subscribed(subscription) =>
            subscription.requestMore(1)
            iterState = AwaitingInput(k)
          case CompleteOrErrorInputWaiting(input) =>
            val iter = k(input)
            getNextStepFromIteratee(iter)
            iterState = Unneeded()
            subState = CompleteOrError
          case CompleteOrError | Unsubscribed =>
            iterState = Unneeded()
        }
      case Unneeded() =>
        ()
      case illegal =>
        throw new IllegalStateException(s"Iteratee state should have been AwaitingStep: $illegal")
    }
  }

  private def onDoneOrErrorStep(r: Try[S]): Unit = exclusive {
    iterState match {
      case AwaitingStep() =>
        subState match {
          case AwaitingSubscription =>
            iterState = AwaitingInput(k)
          case Subscribed(subscription) =>
            subscription.requestMore(1)
            iterState = AwaitingInput(k)
          case CompleteOrErrorInputWaiting(input) =>
            val iter = k(input)
            getNextStepFromIteratee(iter)
            iterState = Unneeded()
            subState = CompleteOrError
          case CompleteOrError | Unsubscribed =>
            iterState = Unneeded()
        }

        iterState = DoneOrError()
      case Unneeded() =>
        ()
      case illegal =>
        throw new IllegalStateException(s"Iteratee state should have been AwaitingStep: $illegal")
    }
    resultPromise.complete(r) // tryComplete?
    subState match {
      case Subscribed(subscription) =>
        subscription.cancel()
      case _ =>
        ()
    }
  }

  override def onError(cause: Throwable): Unit = exclusive {
    subState match {
      case AwaitingSubscription | Subscribed(_) =>
        val (inputOpt, resultOpt) = onErrorInputAndResult(cause)
        inputOpt match {
          case None =>
            subState = Unsubscribed
          case Some(input) =>
            iterState match {
              case AwaitingStep() =>
                subState = Completed(input)
              case AwaitingInput(k) =>
                val iter = k(input)
                getNextStepFromIteratee(iter)
                subState = Unsubscribed
              case Unneeded() =>
                throw new IllegalStateException("Iteratee marked as unneeded while subscription still active")
              case DoneOrError() =>
                ()
            }
        }
        resultOpt.foreach { result: Try[S] =>
          resultPromise.complete(result) // tryComplete?
        }
      case Completed(_) =>
        throw new IllegalStateException("Subscriber can't get an error after subscription completed")
      case Unsubscribed =>
        ()
    }
  }

  override def onComplete(): Unit = exclusive {
    subState match {
      case AwaitingSubscription | Subscribed(_) =>
        iterState match {
          case AwaitingStep() =>
            subState = Completed(Input.EOF)
          case AwaitingInput(k) =>
            val iter = k(Input.EOF)
            getNextStepFromIteratee(iter)
            subState = Unsubscribed
          case Unneeded() =>
            throw new IllegalStateException("Iteratee marked as unneeded while subscription still active")
          case DoneOrError() =>
            ()
        }
      case Completed(_) =>
        throw new IllegalStateException("Subscriber can't be completed twice")
      case Unsubscribed =>
        ()
    }
  }

  override def onNext(element: T): Unit = exclusive {
    subState match {
      case AwaitingSubscription =>
        throw new IllegalStateException("Subscriber received an element before it was subscribed")
      case Subscribed(subscription) =>
        iterState match {
          case AwaitingStep() =>
            throw new IllegalStateException("Subscriber received an element that it hadn't requested")
          case AwaitingInput(k) =>
            val iter = k(Input.El(element))
            getNextStepFromIteratee(iter)
            iterState = AwaitingStep[T,R]()
          case Unneeded() =>
            throw new IllegalStateException("Iteratee marked as unneeded while subscription still active")
          case DoneOrError() =>
            ()
        }
      case Completed(_) =>
        throw new IllegalStateException("Subscriber can't get an element after subscription completed")
      case Unsubscribed =>
        ()
    }
  }

}