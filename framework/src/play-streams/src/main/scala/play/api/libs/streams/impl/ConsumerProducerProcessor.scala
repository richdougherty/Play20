package play.api.libs.streams.impl

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import org.reactivestreams.api._
import org.reactivestreams.spi._
import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Promise }
import scala.util.{ Failure, Success, Try }

final class ConsumerProducerProcessor[T,U](cons: Consumer[T], prod: Producer[U]) extends Processor[T,U] {
  override def getSubscriber: Subscriber[T] = cons.getSubscriber
  override def getPublisher: Publisher[U] = prod.getPublisher
  override def produceTo(consumer: Consumer[U]): Unit = prod.produceTo(consumer)
}