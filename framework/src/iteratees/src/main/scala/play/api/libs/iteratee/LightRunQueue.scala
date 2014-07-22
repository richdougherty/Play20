/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.libs.iteratee

import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future }
import java.util.concurrent.atomic.AtomicReference

private[play] final class LightRunQueue {

  type Op = () => Unit

  private val state = new AtomicReference[Vector[Op]](null)

  def scheduleSimple(body: => Unit): Unit = {
    schedule(() => body)
  }

  @tailrec
  private def schedule(op: Op): Unit = {
    val prevState = state.get
    val newState = prevState match {
      case null => Vector.empty
      case pending => pending :+ op
    }
    if (state.compareAndSet(prevState, newState)) {
      prevState match {
        case null =>
          // We've update the state to say that we're running an op,
          // so we need to actually start it running.
          executeAll(op)
        case _ =>
      }
    } else schedule(op) // Try again
  }

  @tailrec
  private def executeAll(op: Op): Unit = {
    op.apply()
    val nextOp = dequeueNextOpToExecute()
    nextOp match {
      case None => ()
      case Some(op) => executeAll(op)
    }
  }

  @tailrec
  private def dequeueNextOpToExecute(): Option[Op] = {
    val prevState = state.get
    val (newState, nextOp) = prevState match {
      case null => throw new IllegalStateException("When executing, must have a queue of pending elements")
      case pending if pending.isEmpty => (null, None)
      case pending => (pending.tail, Some(pending.head))
    }
    if (state.compareAndSet(prevState, newState)) nextOp else dequeueNextOpToExecute()
  }

}