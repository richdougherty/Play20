/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.libs.streams

import org.reactivestreams._
import play.api.libs.streams.impl._
import org.specs2.mutable.Specification
import play.api.libs.iteratee.{ Enumerator, Input }
import scala.concurrent.duration.{ FiniteDuration => ScalaFiniteDuration, SECONDS, MILLISECONDS }
import scala.concurrent.{ ExecutionContext, Future, Promise }

class StreamsSpec extends Specification {

  "Streams helper interface" should {
    // TODO: Better tests needed, these are only here to ensure Streams compiles
    "create a Publisher from a Future" in {
      val pubr = Streams.futureToPublisher(Future.successful(1))
      pubr must haveClass[FuturePublisher[Int]]
    }
  }

}