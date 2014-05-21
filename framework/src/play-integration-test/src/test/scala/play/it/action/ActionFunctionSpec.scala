/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.it.action

import org.specs2.mutable._
import play.api.test._
import play.api.mvc._
import play.api.mvc.Results._
import scala.concurrent.Future

import scala.language.higherKinds

class ActionFunctionSpec extends PlaySpecification with Controller {

  class NopActionFunction[R[_]] extends ActionFunction[R,R] {
    override def invokeBlock[A](request: R[A], block: R[A] => Future[Result]): Future[Result] = {
      block(request)
    }
  }

  "ActionFunctions" should {

    "be able to build Actions" in {
      val builder = new NopActionFunction[Request]
      val action = builder { request: Request[AnyContent] => Ok("hello") }
      status(action(FakeRequest())) must_== 200
    }

  }

}
