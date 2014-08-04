package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee._
import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }


private[streams] object IterateeConsumer {

  sealed trait State[T,R]
  case class NotSubscribedNoStep[T,R](result: Promise[Iteratee[T,R]]) extends State[T,R]
  case class SubscribedNoStep[T,R](subs: Subscription, result: Promise[Iteratee[T,R]]) extends State[T,R]
  case class NotSubscribedWithCont[T,R](cont: Step.Cont[T,R], result: Promise[Iteratee[T,R]]) extends State[T,R]
  case class SubscribedWithCont[T,R](subs: Subscription, cont: Step.Cont[T,R], result: Promise[Iteratee[T,R]]) extends State[T,R]
  case class CompletedNoStep[T,R](result: Promise[Iteratee[T,R]]) extends State[T,R]
  case class Finished[T,R](resultIteratee: Iteratee[T,R]) extends State[T,R]
}

class IterateeConsumer[T,R,S](iter0: Iteratee[T,R]) extends Consumer[T] with Subscriber[T] {
  import IterateeConsumer._

  // Use a RunQueue to implement a lock-free mutex
  private val runQueue = new LightRunQueue()

  var state: State[T,R] = NotSubscribedNoStep(Promise[Iteratee[T,R]]())
  getNextStepFromIteratee(iter0)


  // private val runQueue = new LightRunQueue()
  // private def exclusive(f: State[T, R] => Unit) = runQueue.scheduleSimple {
  //   f(state)
  // }

  private def debug(msg: String) = ()//println(s"${Thread.currentThread}: $msg")

  private def exclusive(msg: => String)(f: State[T, R] => Unit): Unit = {
    runQueue.scheduleSimple {
      debug(msg)
      debug(s"State: $state")
      f(state)
      debug(s"   --> $state")
    }
    // debug(s"exclusive finished")
  }

  //exclusive((st: State[T, R]) => debug(s"harcoded call to exclusive: $st"))

  def result: Iteratee[T,R] = state match {
    case NotSubscribedNoStep(result) =>
      promiseToIteratee(result)
    case SubscribedNoStep(subs, result) =>
      promiseToIteratee(result)
    case NotSubscribedWithCont(cont, result) =>
      promiseToIteratee(result)
    case SubscribedWithCont(subs, cont, result) =>
      promiseToIteratee(result)
    case CompletedNoStep(result) =>
      promiseToIteratee(result)
    case Finished(resultIteratee) =>
      resultIteratee
  }

  // Streams API method
  override val getSubscriber: Subscriber[T] = this

  // Streams SPI methods
  override def onSubscribe(subs: Subscription): Unit = exclusive("onSubscribe") {
    case NotSubscribedNoStep(result) =>
      state = SubscribedNoStep(subs, result)
    case SubscribedNoStep(subs, result) =>
      throw new IllegalStateException("Can't subscribe twice")
    case NotSubscribedWithCont(cont, result) =>
      subs.requestMore(1)
      state = SubscribedWithCont(subs, cont, result)
    case SubscribedWithCont(subs, cont, result) =>
      throw new IllegalStateException("Can't subscribe twice")
    case CompletedNoStep(result) =>
      throw new IllegalStateException("Can't subscribe once completed")
    case Finished(resultIteratee) =>
      subs.cancel()
  }

  override def onComplete(): Unit = exclusive("onComplete") {
    case NotSubscribedNoStep(result) =>
      state = CompletedNoStep(result)
    case SubscribedNoStep(subs, result) =>
      state = CompletedNoStep(result)
    case NotSubscribedWithCont(cont, result) =>
      finishWithCompletedCont(cont, result)
    case SubscribedWithCont(subs, cont, result) =>
      finishWithCompletedCont(cont, result)
    case CompletedNoStep(result) =>
      throw new IllegalStateException("Can't complete twice")
    case Finished(resultIteratee) =>
      ()
  }

  override def onError(cause: Throwable): Unit = exclusive("onError(...)") {
    case NotSubscribedNoStep(result) =>
      finishWithError(cause, result)
    case SubscribedNoStep(subs, result) =>
      finishWithError(cause, result)
    case NotSubscribedWithCont(cont, result) =>
      finishWithError(cause, result)
    case SubscribedWithCont(subs, cont, result) =>
      finishWithError(cause, result)
    case CompletedNoStep(result) =>
      throw new IllegalStateException("Can't receive error once completed")
    case Finished(resultIteratee) =>
      ()
  }

  override def onNext(element: T): Unit = exclusive(s"onNext($element)") {
    case NotSubscribedNoStep(result) =>
      throw new IllegalStateException("Got next element before subscribed")
    case SubscribedNoStep(subs, result) =>
      throw new IllegalStateException("Got next element before requested")
    case NotSubscribedWithCont(cont, result) =>
      throw new IllegalStateException("Got next element before subscribed")
    case SubscribedWithCont(subs, cont, result) =>
      continueWithNext(subs, cont, element, result)
      // val nextIteratee = cont.k(Input.El(element))
      // getNextStepFromIteratee(nextIteratee)
      // state = SubscribedNoStep(subs, result)
    case CompletedNoStep(result) =>
      throw new IllegalStateException("Can't receive error once completed")
    case Finished(resultIteratee) =>
      ()
  }

  def continueWithNext(subs: Subscription, cont: Step.Cont[T,R], element: T, result: Promise[Iteratee[T,R]]): Unit = {
    val nextIteratee = cont.k(Input.El(element))
    getNextStepFromIteratee(nextIteratee)
    state = SubscribedNoStep(subs, result)
  }

  private def onContStep(cont: Step.Cont[T,R]): Unit = exclusive("onContStep(...)") {
    case NotSubscribedNoStep(result) =>
      state = NotSubscribedWithCont(cont, result)
    case SubscribedNoStep(subs, result) =>
      subs.requestMore(1)
      state = SubscribedWithCont(subs, cont, result)
    case NotSubscribedWithCont(cont, result) =>
      throw new IllegalStateException("Can't get cont twice")
    case SubscribedWithCont(subs, cont, result) =>
      throw new IllegalStateException("Can't get cont twice")
    case CompletedNoStep(result) =>
      finishWithCompletedCont(cont, result)
    case Finished(resultIteratee) =>
      ()
  }

  private def onDoneOrErrorStep(doneOrError: Step[T,R]): Unit = exclusive("onDoneOrErrorStep(...)") {
    case NotSubscribedNoStep(result) =>
      finishWithStep(doneOrError, result)
    case SubscribedNoStep(subs, result) =>
      finishWithStep(doneOrError, result)
    case NotSubscribedWithCont(cont, result) =>
      throw new IllegalStateException("Can't get done or error while has cont")
    case SubscribedWithCont(subs, cont, result) =>
      throw new IllegalStateException("Can't get done or error while has cont")
    case CompletedNoStep(result) =>
      finishWithStep(doneOrError, result)
    case Finished(resultIteratee) =>
      ()
  }

  private def getNextStepFromIteratee(iter: Iteratee[T,R]): Unit = {
    iter.pureFold {
      case c@Step.Cont(_) => onContStep(c)
      case d@Step.Done(_, _) => onDoneOrErrorStep(d)
      case e@Step.Error(_, _) => onDoneOrErrorStep(e)
    }(Execution.trampoline)
  }

  private def promiseToIteratee(result: Promise[Iteratee[T,R]]) = Iteratee.flatten(result.future)

  private def finishWithCompletedCont(cont: Step.Cont[T,R], result: Promise[Iteratee[T,R]]): Unit = {
    val nextIteratee = cont.k(Input.EOF)
    result.success(nextIteratee)
    state = Finished(nextIteratee)
  }

  private def finishWithError(cause: Throwable, result: Promise[Iteratee[T,R]]): Unit = {
    result.failure(cause)
    state = Finished(promiseToIteratee(result))
  }

  private def finishWithStep(step: Step[T,R], result: Promise[Iteratee[T,R]]): Unit = {
    val nextIteratee = step.it
    result.success(nextIteratee)
    state = Finished(nextIteratee)
  }

}