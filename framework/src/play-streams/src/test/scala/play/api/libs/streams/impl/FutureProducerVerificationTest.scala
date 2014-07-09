package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import org.reactivestreams.api._
import org.reactivestreams.spi._
import org.reactivestreams.tck.{ TestEnvironment, PublisherVerification }
import org.scalatest.testng.TestNGSuiteLike
import org.testng.SkipException
import play.api.libs.iteratee.Execution
import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

class FutureProducerVerificationTest extends PublisherVerification[Int](
  new TestEnvironment(2000),
  2000
) with TestNGSuiteLike {
  def createPublisher(elements: Int): Publisher[Int] = {
    if (elements != 1) throw new SkipException(s"Can only create a Publisher with a single element: $elements")
    new FutureProducer(Future.successful(1))
  }

  override def createCompletedStatePublisher(): Publisher[Int] = (null: FutureProducer[Int])

  override def createErrorStatePublisher(): Publisher[Int] = new FutureProducer[Int](Future.failed(new Exception("Fake error")))

}