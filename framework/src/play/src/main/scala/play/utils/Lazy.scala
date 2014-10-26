/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.utils

import scala.util.Try

/**
 * A value whose evaluation is delayed. A 'Lazy' value is roughly equivalent
 * to Scala's `lazy` keyword, except it can be copied and moved around without
 * forcing evaluation.
 */
private[play] trait Lazy[+A] {
  top =>
  def get: A
}

/**
 * A fast lazy value that doesn't synchronize its evaluation. This lazy value
 * is OK to use if it will only be used from a single thread, or if it is OK
 * to be evaluated multiple times.
 */
private[play] class UnsynchronizedLazy[A](thunk: () => A) extends Lazy[A] {
  private var value: Try[A] = null // use `null` to mean "not evaluated"
  def get: A = {
    if (value == null) {
      value = Try(thunk())
    }
    value.get
  }
  def map[B](f: A => B): UnsynchronizedLazy[B] =
    new UnsynchronizedLazy(() => f(thunk()))
  def flatMap[B](f: A => UnsynchronizedLazy[B]): UnsynchronizedLazy[B] =
    new UnsynchronizedLazy(() => f(thunk()).get)
}

object UnsynchronizedLazy {
  def apply[A](thunk: => A) = new UnsynchronizedLazy(() => thunk)
}

private[play] class EvaluatedLazy[+A](value: A) extends Lazy[A] {
  def get = value
}