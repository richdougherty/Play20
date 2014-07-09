/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.libs.streams.impl

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.api._
import org.reactivestreams.spi._
import play.api.libs.iteratee.Execution
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

/**
 * Common functionality for iteratee tests.
 */
class FutureProducerSpec extends Specification with Mockito {

  sealed trait Event
  case object GetSubscriber extends Event
  case object OnSubscribe extends Event
  case class OnError(t: Throwable) extends Event
  case class OnNext(element: Any) extends Event
  case object OnComplete extends Event
  case class RequestMore(elementCount: Int) extends Event
  case object Cancel extends Event
  case object GetSubscription extends Event

  class TestEnv[T](maxTime: ScalaFiniteDuration = ScalaFiniteDuration(10, SECONDS)) {
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


  "FutureProducer" should {
    "produce immediate success results" in {
      val testEnv = new TestEnv[Int]
      val fut = Future.successful(1)
      val prod = new FutureProducer(fut)
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnSubscribe
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.next must_== OnNext(1)
      testEnv.next must_== OnComplete
      testEnv.isEmptyAfterDelay() must beTrue
    }
    "produce immediate failure results" in {
      val testEnv = new TestEnv[Int]
      val e = new Exception("test failure")
      val fut: Future[Int] = Future.failed(e)
      val prod = new FutureProducer(fut)
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnError(e)
      testEnv.isEmptyAfterDelay() must beTrue
    }
    "produce delayed success results" in {
      val testEnv = new TestEnv[Int]
      val prom = Promise[Int]()
      val prod = new FutureProducer(prom.future)
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnSubscribe
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.isEmptyAfterDelay() must beTrue
      prom.success(3)
      testEnv.next must_== OnNext(3)
      testEnv.next must_== OnComplete
      testEnv.isEmptyAfterDelay() must beTrue
    }
    "produce delayed failure results" in {
      val testEnv = new TestEnv[Int]
      val prom = Promise[Int]()
      val prod = new FutureProducer(prom.future)
      prod.produceTo(testEnv.consumer)
      testEnv.next must_== GetSubscriber
      testEnv.next must_== OnSubscribe
      testEnv.requestMore(1)
      testEnv.next must_== RequestMore(1)
      testEnv.isEmptyAfterDelay() must beTrue
      val e = new Exception("test failure")
      prom.failure(e)
      testEnv.next must_== OnError(e)
      testEnv.isEmptyAfterDelay() must beTrue
    }
  }

}