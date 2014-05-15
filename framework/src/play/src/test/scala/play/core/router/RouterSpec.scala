/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.router

import org.specs2.mutable.Specification
import play.core.{Router, PathPattern, DynamicPart, StaticPart}

object RouterSpec extends Specification {

  object SingletonRoutes extends Router.Routes {
    private var _prefix = "/"
    def setPrefix(prefix: String) {
      _prefix = prefix
    }
    def prefix = _prefix
    def documentation = Nil
    def routes = Map.empty
  }

  class InstanceRoutes(context: Router.RoutesContext) extends Router.Routes {
    def setPrefix(prefix: String) = ???
    def prefix = context.prefix
    def documentation = Nil
    def routes = Map.empty
  }

  class InvalidRoutes extends Router.Routes {
    def setPrefix(prefix: String) = ???
    def prefix = ???
    def documentation = Nil
    def routes = Map.empty
  }

  "Router loading" should {

    val classLoader = getClass.getClassLoader
    val specClassName = getClass.getName.dropRight(1)

    "load singleton routes and set the prefix" in {
      // Force singleton prefix to a known value before running test
      SingletonRoutes.setPrefix("/x")
      SingletonRoutes.prefix must_== "/x"

      val context = Router.RoutesContext(application = null, prefix = "/hello")
      val loadResult = Router.Routes.load(classLoader, specClassName + "$SingletonRoutes", context)
      loadResult must beRight.like {
        case Some(routes) =>
          routes must_== SingletonRoutes
          routes.prefix must_== "/hello"
      }
    }

    "instantiate routes class with a context" in {
      val context = Router.RoutesContext(application = null, prefix = "/new")
      val loadResult = Router.Routes.load(classLoader, specClassName + "$InstanceRoutes", context)
      loadResult must beRight.like {
        case Some(routes) =>
          routes.getClass must_== classOf[InstanceRoutes]
          routes.prefix must_== "/new"
      }
    }

    "not load unknown classes" in {
      val context = Router.RoutesContext(application = null, prefix = "/hello")
      val loadResult = Router.Routes.load(classLoader, "ElephantRoutes", context)
      loadResult must_== Right(None)
    }

    "not instantiate a class that doesn't have a context constructor" in {
      val context = Router.RoutesContext(application = null, prefix = "/hello")
      val loadResult = Router.Routes.load(classLoader, specClassName + "$InvalidRoutes", context)
      loadResult must_== Left("Router was missing a RoutesContext constructor: play.core.router.RouterSpec$InvalidRoutes")
    }

  }

  "Router dynamic string builder" should {
    "handle empty parts" in {
      Router.dynamicString("") must_== ""
    }
    "handle simple parts" in {
      Router.dynamicString("xyz") must_== "xyz"
    }
    "handle parts containing backslashes" in {
      Router.dynamicString("x/y") must_== "x%2Fy"
    }
    "handle parts containing spaces" in {
      Router.dynamicString("x y") must_== "x%20y"
    }
    "handle parts containing pluses" in {
      Router.dynamicString("x+y") must_== "x+y"
    }
    "handle parts with unicode characters" in {
      Router.dynamicString("ℛat") must_== "%E2%84%9Bat"
    }
  }

  "Router queryString builder" should {
    "build a query string" in {
      Router.queryString(List(Some("a"), Some("b"))) must_== "?a&b"
    }
    "ignore none values" in {
      Router.queryString(List(Some("a"), None, Some("b"))) must_== "?a&b"
      Router.queryString(List(None, Some("a"), None)) must_== "?a"
    }
    "ignore empty values" in {
      Router.queryString(List(Some("a"), Some(""), Some("b"))) must_== "?a&b"
      Router.queryString(List(Some(""), Some("a"), Some(""))) must_== "?a"
    }
    "produce nothing if no values" in {
      Router.queryString(List(None, Some(""))) must_== ""
      Router.queryString(List()) must_== ""
    }
  }

  "PathPattern" should {
    val pathPattern = PathPattern(Seq(StaticPart("/path/"), StaticPart("to/"), DynamicPart("foo", "[^/]+", true)))
    val pathString = "/path/to/some%20file"
    val pathNonEncodedString1 = "/path/to/bar:baz"
    val pathNonEncodedString2 = "/path/to/bar:%20baz"
    val pathStringInvalid = "/path/to/invalide%2"

    "Bind Path string as string" in {
      pathPattern(pathString).get("foo") must beEqualTo(Right("some file"))
    }
    "Bind Path with incorrectly encoded string as string" in {
      pathPattern(pathNonEncodedString1).get("foo") must beEqualTo(Right("bar:baz"))
    }
    "Bind Path with incorrectly encoded string as string" in {
      pathPattern(pathNonEncodedString2).get("foo") must beEqualTo(Right("bar: baz"))
    }
    "Fail on unparseable Path string" in {
      val Left(e) = pathPattern(pathStringInvalid).get("foo")
      e.getMessage must beEqualTo("Malformed escape pair at index 9: /invalide%2")
    }

    "multipart path is not decoded" in {
      val pathPattern = PathPattern(Seq(StaticPart("/path/"), StaticPart("to/"), DynamicPart("foo", ".+", false)))
      val pathString = "/path/to/this/is/some%20file/with/id"
      pathPattern(pathString).get("foo") must beEqualTo(Right("this/is/some%20file/with/id"))

    }
  }
}
