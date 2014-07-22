/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.libs.streams.impl

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee.Execution
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Input
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

class EnumeratorProducerSpec extends Specification with Mockito {

  sealed trait Event
  case object GetSubscriber extends Event
  case object OnSubscribe extends Event
  case class OnError(t: Throwable) extends Event
  case class OnNext(element: Any) extends Event
  case object OnComplete extends Event
  case class RequestMore(elementCount: Int) extends Event
  case object Cancel extends Event
  case object GetSubscription extends Event

  class TestEnv[T](maxTime: ScalaFiniteDuration = ScalaFiniteDuration(2, SECONDS)) {
    val events = new LinkedBlockingQueue[Event]
    def record(e: Event) = events.add(e)

    val deadline = ScalaDeadline.now + maxTime
    def next(): Event = {
      val remaining = deadline.timeLeft
      events.poll(remaining.length, remaining.unit)
    }
    def isEmptyAfterDelay(waitMillis: Long = 50): Boolean = {
      Thread.sleep(waitMillis)
      events.isEmpty
    }

    object consumer extends Consumer[T] {
      object subscriber extends Subscriber[T] {
        val subscription = Promise[Subscription]()
        override def onSubscribe(s: Subscription) = {
          record(OnSubscribe)
          subscription.success(s)
        }
        override def onError(t: Throwable) = record(OnError(t))
        override def onNext(element: T) = record(OnNext(element))
        override def onComplete() = record(OnComplete)
      }
      def getSubscriber(): Subscriber[T] = {
        record(GetSubscriber)
        subscriber
      }
    }

    def forSubscription(f: Subscription => Any): Future[Unit] = {
      consumer.subscriber.subscription.future.map(f).map(_ => ())
    }
    def requestMore(elementCount: Int): Future[Unit] = {
      forSubscription { s =>
        record(RequestMore(elementCount))
        s.requestMore(elementCount)
      }
    }
    def cancel(): Future[Unit] = {
      forSubscription { s =>
        record(Cancel)
        s.cancel()
      }
    }

  }


  "EnumeratorProducer" should {
    "enumerate one item" in {
      val testEnv = new TestEnv[Int]
      val enum = Enumerator(1) >>> Enumerator.eof
      val prod = new EnumeratorProducer(enum)
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnSubscribe
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.next must_== OnNext(1)
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.next must_== OnComplete
      testEnv.isEmptyAfterDelay() must beTrue
    }
    "enumerate three items, with batched requestMores" in {
      val testEnv = new TestEnv[Int]
      val enum = Enumerator(1, 2, 3) >>> Enumerator.eof
      val prod = new EnumeratorProducer(enum)
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnSubscribe
      testEnv.requestMore(2)
      testEnv.next must_== RequestMore(2)
      testEnv.next must_== OnNext(1)
      testEnv.next must_== OnNext(2)
      testEnv.requestMore(2)
      testEnv.next must_== RequestMore(2)
      testEnv.next must_== OnNext(3)
      testEnv.next must_== OnComplete
      testEnv.isEmptyAfterDelay() must beTrue
    }
    "enumerate eof only" in {
      val testEnv = new TestEnv[Int]
      val enum: Enumerator[Int] = Enumerator.eof
      val prod = new EnumeratorProducer(enum)
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnSubscribe
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.next must_== OnComplete
      testEnv.isEmptyAfterDelay() must beTrue
    }
    "by default, enumerate nothing for empty" in {
      val testEnv = new TestEnv[Int]
      val enum: Enumerator[Int] = Enumerator.enumInput(Input.Empty) >>> Enumerator.eof
      val prod = new EnumeratorProducer(enum)
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnSubscribe
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.next must_== OnComplete
      testEnv.isEmptyAfterDelay() must beTrue
    }
    "be able to enumerate something for empty" in {
      val testEnv = new TestEnv[Int]
      val enum: Enumerator[Int] = Enumerator.enumInput(Input.Empty) >>> Enumerator.eof
      val prod = new EnumeratorProducer(enum, emptyElement = Some(-1))
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnSubscribe
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.next must_== OnNext(-1)
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.next must_== OnComplete
      testEnv.isEmptyAfterDelay() must beTrue
    }
    "enumerate 25 items" in {
      val testEnv = new TestEnv[Int]
      val lotsOfItems = 0 until 25
      val enum = Enumerator(lotsOfItems: _*) >>> Enumerator.eof
      val prod = new EnumeratorProducer(enum)
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnSubscribe
      for (i <- lotsOfItems) {
        testEnv.requestMore(1)
        testEnv.next must_== RequestMore(1)
        testEnv.next must_== OnNext(i)
      }
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.next must_== OnComplete
      testEnv.isEmptyAfterDelay() must beTrue
    }
 }

}