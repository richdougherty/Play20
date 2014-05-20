/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.mvc

import org.specs2.mutable._
import play.api.mvc.Results._
import scala.concurrent.Future

import scala.language.higherKinds

class ActionFunctionSpec extends Specification {

  class NopActionFunction[R[_]] extends ActionFunction[R,R] {
    override def invokeBlock[A](request: R[A], block: R[A] => Future[Result]): Future[Result] = {
      block(request)
    }
  }

  "ActionFunctions" should {

    "be able to build Actions" in {
      (new NopActionFunction[Request]) { request => Ok("hello") }
    }

  }

}
