/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.libs.streams.impl

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee._
import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

// import play.api.libs.iteratee.internal.executeFuture
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration.{ Deadline => ScalaDeadline, Duration => ScalaDuration, FiniteDuration => ScalaFiniteDuration, SECONDS, MILLISECONDS }
import org.specs2.mutable.Specification
import scala.util.Try
import org.specs2.mock.Mockito
//import org.mockito._

class IterateeConsumerSpec extends Specification with Mockito {

  //sealed trait Event
  //case object ProduceTo extends Event
  // case object OnSubscribe extends Event
  // case class OnError(t: Throwable) extends Event
  // case class OnNext(element: Any) extends Event
  // case object OnComplete extends Event
  case class RequestMore(elementCount: Int) //extends Event
  case object Cancel //extends Event
  // case object ContStep extends Event
  // case object DoneStep extends Event
  // case object ErrorStep extends Event
  case class ContInput(input: Input[_]) //extends Event
  case class Result(result: Any) //extends Event

  class TestEnv[T](maxTime: ScalaFiniteDuration = ScalaFiniteDuration(2, SECONDS)) {
    val events = new LinkedBlockingQueue[Any]
    def record(e: Any) = events.add(e)

    @volatile
    var nextIterateePromise = Promise[Iteratee[T,T]]()
    def nextIteratee = Iteratee.flatten(nextIterateePromise.future)

    // Initialize
    {
      val iter = nextIteratee
      val cons = new IterateeConsumer(iter)
      cons.result.unflatten.onComplete { tryStep: Try[Step[T,T]] =>
        record(Result(tryStep))
      
      }
      producer.produceTo(cons)
    }

    def contStep(): Unit = {
      //record(ContStep)
      val oldPromise = nextIterateePromise
      nextIterateePromise = Promise[Iteratee[T,T]]()
      oldPromise.success(Cont { input =>
        record(ContInput(input))
        nextIteratee
      })
    }

    def doneStep(result: T, remaining: Input[T]): Unit = {
      //record(DoneStep)
      nextIterateePromise.success(Done(result, remaining))
    }

    def errorStep(msg: String, input: Input[T]): Unit = {
      //record(ErrorStep)
      nextIterateePromise.success(Error(msg, input))
    }

    val deadline = ScalaDeadline.now + maxTime
    def next(): Any = {
      val remaining = deadline.timeLeft
      events.poll(remaining.length, remaining.unit)
    }
    def isEmptyAfterDelay(waitMillis: Long = 50): Boolean = {
      Thread.sleep(waitMillis)
      events.isEmpty
    }

    object producer extends Producer[T] {
      object publisher extends Publisher[T] {
        object subscription extends Subscription {
          val subscriber = Promise[Subscriber[T]]()

          def cancel(): Unit = {
            record(Cancel)
          }

          def requestMore(elements: Int): Unit = {
            record(RequestMore(elements))
          }
        }
        override def subscribe(s: Subscriber[T]) = {
          subscription.subscriber.success(s)
        }
      }
      final override def getPublisher: Publisher[T] = publisher
      final override def produceTo(consumer: Consumer[T]): Unit = {
        getPublisher.subscribe(consumer.getSubscriber)
        //record(ProduceTo)
      }

    }

    def forSubscriber(f: Subscriber[T] => Any): Future[Unit] = {
      producer.publisher.subscription.subscriber.future.map(f).map(_ => ())
    }

    def onSubscribe(): Unit = {
      forSubscriber { s =>
        //record(OnSubscribe)
        s.onSubscribe(producer.publisher.subscription)
      }
    }

    def onNext(element: T): Unit = {
      forSubscriber { s =>
        //record(OnNext(element))
        s.onNext(element)
      }
    }

    def onError(t: Throwable): Unit = {
      forSubscriber { s =>
        //record(OnError(t))
        s.onError(t)
      }
    }

    def onComplete(): Unit = {
      forSubscriber { s =>
        //record(OnComplete)
        s.onComplete()
      }
    }

  }

  "IterateeConsumer" should {
    "consume 1 item" in {
      val enum = Enumerator(1, 2, 3) >>> Enumerator.eof
      val prod = new EnumeratorProducer(enum)
      val iter = Iteratee.getChunks[Int]
      val cons = new IterateeConsumer(iter)
      prod.produceTo(cons)
      Await.result(cons.result.unflatten, ScalaFiniteDuration(2, SECONDS)) must_== Done(List(1, 2, 3), Input.EOF)
    }

    "consume one element (on-subscribe/cont-step/on-next/cont-step/on-complete/done-step)" in {
      val testEnv = new TestEnv[Int]
      testEnv.onSubscribe()
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.contStep()
      testEnv.next must_== RequestMore(1)
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onNext(1)
      testEnv.next must_== ContInput(Input.El(1))
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.contStep()
      testEnv.next must_== RequestMore(1)
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onComplete()
      testEnv.next must_== ContInput(Input.EOF)
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.doneStep(123, Input.Empty)
      testEnv.next must_== Result(Success(Step.Done(123, Input.Empty)))
      testEnv.isEmptyAfterDelay() must beTrue
    }

    "consume one element (cont-step/on-subscribe/on-next/cont-step/on-complete/done-step)" in {
      val testEnv = new TestEnv[Int]

      testEnv.contStep()
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onSubscribe()
      testEnv.next must_== RequestMore(1)
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onNext(1)
      testEnv.next must_== ContInput(Input.El(1))
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.contStep()
      testEnv.next must_== RequestMore(1)
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onComplete()
      testEnv.next must_== ContInput(Input.EOF)
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.doneStep(123, Input.Empty)
      testEnv.next must_== Result(Success(Step.Done(123, Input.Empty)))
      testEnv.isEmptyAfterDelay() must beTrue
    }

    "send EOF to cont when producer completes immediately, moving to cont (cont-step/on-complete/cont-step)" in {
      val testEnv = new TestEnv[Int]

      testEnv.contStep()
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onComplete()
      testEnv.next must_== ContInput(Input.EOF)
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.contStep()
      testEnv.next must beLike { case Result(Success(Step.Cont(_))) => ok }
      testEnv.isEmptyAfterDelay() must beTrue
    }

    "send EOF to cont when producer completes immediately, moving to error (on-complete/cont-step/error-step)" in {
      val testEnv = new TestEnv[Int]

      testEnv.onComplete()
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.contStep()
      testEnv.next must_== ContInput(Input.EOF)
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.errorStep("!!!", Input.EOF)
      testEnv.next must_== Result(Success(Step.Error("!!!", Input.EOF)))
      testEnv.isEmptyAfterDelay() must beTrue
    }

    "fail when producer errors immediately (on-error)" in {
      val testEnv = new TestEnv[Int]

      val t = new Exception("%@^%#!")
      testEnv.onError(t)
      testEnv.next must_== Result(Failure(t))
      testEnv.isEmptyAfterDelay() must beTrue
    }

    "fail when producer errors immediately (cont-step/on-error)" in {
      val testEnv = new TestEnv[Int]

      testEnv.contStep()
      testEnv.isEmptyAfterDelay() must beTrue

      val t = new Exception("%@^%#!")
      testEnv.onError(t)
      testEnv.next must_== Result(Failure(t))
      testEnv.isEmptyAfterDelay() must beTrue
    }

    "finish when iteratee is done immediately, cancel subscription (done-step/on-subscribe)" in {
      val testEnv = new TestEnv[Int]

      testEnv.doneStep(333, Input.El(99))
      testEnv.next must_== Result(Success(Done(333, Input.El(99))))
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onSubscribe()
      testEnv.next must_== Cancel
      testEnv.isEmptyAfterDelay() must beTrue
    }

    "finish when iteratee is done immediately, ignore complete (done-step/on-complete)" in {
      val testEnv = new TestEnv[Int]

      testEnv.doneStep(333, Input.El(99))
      testEnv.next must_== Result(Success(Done(333, Input.El(99))))
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onComplete()
      testEnv.isEmptyAfterDelay() must beTrue
    }

    "finish when iteratee is done immediately, ignore error (done-step/on-error)" in {
      val testEnv = new TestEnv[Int]

      testEnv.doneStep(333, Input.El(99))
      testEnv.next must_== Result(Success(Done(333, Input.El(99))))
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onError(new Exception("x"))
      testEnv.isEmptyAfterDelay() must beTrue
    }

    "finish when iteratee errors immediately, cancel subscription (done-step/on-subscribe)" in {
      val testEnv = new TestEnv[Int]

      testEnv.errorStep("iteratee error", Input.El(99))
      testEnv.next must_== Result(Success(Error("iteratee error", Input.El(99))))
      testEnv.isEmptyAfterDelay() must beTrue

      testEnv.onSubscribe()
      testEnv.next must_== Cancel
      testEnv.isEmptyAfterDelay() must beTrue
    }

  }

 //  "EnumeratorProducer" should {
 //    "enumerate one item" in {
 //      val testEnv = new TestEnv[Int]
 //      val enum = Enumerator(1) >>> Enumerator.eof
 //      val prod = new EnumeratorProducer(enum)
 //      prod.produceTo(testEnv.consumer)
 //      testEnv.next must_== GetSubscriber
 //      testEnv.next must_== OnSubscribe
 //      testEnv.requestMore(1)
 //      testEnv.next must_== RequestMore(1)
 //      testEnv.next must_== OnNext(1)
 //      testEnv.requestMore(1)
 //      testEnv.next must_== RequestMore(1)
 //      testEnv.next must_== OnComplete
 //      testEnv.isEmptyAfterDelay() must beTrue
 //    }
 //    "enumerate three items, with batched requestMores" in {
 //      val testEnv = new TestEnv[Int]
 //      val enum = Enumerator(1, 2, 3) >>> Enumerator.eof
 //      val prod = new EnumeratorProducer(enum)
 //      prod.produceTo(testEnv.consumer)
 //      testEnv.next must_== GetSubscriber
 //      testEnv.next must_== OnSubscribe
 //      testEnv.requestMore(2)
 //      testEnv.next must_== RequestMore(2)
 //      testEnv.next must_== OnNext(1)
 //      testEnv.next must_== OnNext(2)
 //      testEnv.requestMore(2)
 //      testEnv.next must_== RequestMore(2)
 //      testEnv.next must_== OnNext(3)
 //      testEnv.next must_== OnComplete
 //      testEnv.isEmptyAfterDelay() must beTrue
 //    }
 //    "enumerate eof only" in {
 //      val testEnv = new TestEnv[Int]
 //      val enum: Enumerator[Int] = Enumerator.eof
 //      val prod = new EnumeratorProducer(enum)
 //      prod.produceTo(testEnv.consumer)
 //      testEnv.next must_== GetSubscriber
 //      testEnv.next must_== OnSubscribe
 //      testEnv.requestMore(1)
 //      testEnv.next must_== RequestMore(1)
 //      testEnv.next must_== OnComplete
 //      testEnv.isEmptyAfterDelay() must beTrue
 //    }
 //    "by default, enumerate nothing for empty" in {
 //      val testEnv = new TestEnv[Int]
 //      val enum: Enumerator[Int] = Enumerator.enumInput(Input.Empty) >>> Enumerator.eof
 //      val prod = new EnumeratorProducer(enum)
 //      prod.produceTo(testEnv.consumer)
 //      testEnv.next must_== GetSubscriber
 //      testEnv.next must_== OnSubscribe
 //      testEnv.requestMore(1)
 //      testEnv.next must_== RequestMore(1)
 //      testEnv.next must_== OnComplete
 //      testEnv.isEmptyAfterDelay() must beTrue
 //    }
 //    "be able to enumerate something for empty" in {
 //      val testEnv = new TestEnv[Int]
 //      val enum: Enumerator[Int] = Enumerator.enumInput(Input.Empty) >>> Enumerator.eof
 //      val prod = new EnumeratorProducer(enum, emptyElement = Some(-1))
 //      prod.produceTo(testEnv.consumer)
 //      testEnv.next must_== GetSubscriber
 //      testEnv.next must_== OnSubscribe
 //      testEnv.requestMore(1)
 //      testEnv.next must_== RequestMore(1)
 //      testEnv.next must_== OnNext(-1)
 //      testEnv.requestMore(1)
 //      testEnv.next must_== RequestMore(1)
 //      testEnv.next must_== OnComplete
 //      testEnv.isEmptyAfterDelay() must beTrue
 //    }
 //    "enumerate 25 items" in {
 //      val testEnv = new TestEnv[Int]
 //      val lotsOfItems = 0 until 25
 //      val enum = Enumerator(lotsOfItems: _*) >>> Enumerator.eof
 //      val prod = new EnumeratorProducer(enum)
 //      prod.produceTo(testEnv.consumer)
 //      testEnv.next must_== GetSubscriber
 //      testEnv.next must_== OnSubscribe
 //      for (i <- lotsOfItems) {
 //        testEnv.requestMore(1)
 //        testEnv.next must_== RequestMore(1)
 //        testEnv.next must_== OnNext(i)
 //      }
 //      testEnv.requestMore(1)
 //      testEnv.next must_== RequestMore(1)
 //      testEnv.next must_== OnComplete
 //      testEnv.isEmptyAfterDelay() must beTrue
 //    }
 // }

}