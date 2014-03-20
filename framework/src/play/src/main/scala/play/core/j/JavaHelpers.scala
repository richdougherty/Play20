/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.j

import java.util.{ HashMap => JHashMap, Map => JMap }
import play.mvc.{ SimpleResult => JSimpleResult }
import play.mvc.Http.{ Context => JContext, Cookie => JCookie, Flash => JFlash, Request => JRequest, Response => JResponse, Cookies => JCookies, Session => JSession }

import play.libs.F
import play.libs.LazyDirtyMap
import scala.concurrent.Future
import play.api.libs.iteratee.Execution.trampoline

class EitherToFEither[A, B]() extends play.libs.F.Function[Either[A, B], play.libs.F.Either[A, B]] {

  def apply(e: Either[A, B]): play.libs.F.Either[A, B] = e.fold(play.libs.F.Either.Left(_), play.libs.F.Either.Right(_))

}

/**
 *
 * provides helper methods that manage java to scala Result and scala to java Context
 * creation
 */
trait JavaHelpers {
  import collection.JavaConverters._
  import play.api.mvc._
  import play.mvc.Http.RequestBody

  /**
   * creates a scala result from java context and result objects
   * @param javaContext
   * @param javaResult
   */
  def createResult(javaContext: JContext, javaResult: JSimpleResult): SimpleResult = {
    val wResult = javaResult.getWrappedSimpleResult.withHeaders(javaContext.response.getHeaders.asScala.toSeq: _*)
      .withCookies((javaContext.response.cookies.asScala.toSeq map { c =>
        Cookie(c.name, c.value,
          if (c.maxAge == null) None else Some(c.maxAge), c.path, Option(c.domain), c.secure, c.httpOnly)
      }): _*)

    if (javaContext.session.isDirty && javaContext.flash.isDirty) {
      wResult.withSession(Session(javaContext.session.asScala.toMap)).flashing(Flash(javaContext.flash.asScala.toMap))
    } else {
      if (javaContext.session.isDirty) {
        wResult.withSession(Session(javaContext.session.asScala.toMap))
      } else {
        if (javaContext.flash.isDirty) {
          wResult.flashing(Flash(javaContext.flash.asScala.toMap))
        } else {
          wResult
        }
      }
    }
  }

  /**
   * creates a java request (with an empty body) from a scala RequestHeader
   * @param request incoming requestHeader
   */
  def createJavaRequest(req: RequestHeader): JRequest = {
    new JRequest {

      def uri = req.uri

      def method = req.method

      def version = req.version

      def remoteAddress = req.remoteAddress

      def secure = req.secure

      def host = req.host

      def path = req.path

      def body = null

      def headers = req.headers.toMap.map(e => e._1 -> e._2.toArray).asJava

      def acceptLanguages = req.acceptLanguages.map(new play.i18n.Lang(_)).asJava

      def queryString = {
        req.queryString.mapValues(_.toArray).asJava
      }

      def accept = req.accept.asJava

      def acceptedTypes = req.acceptedTypes.asJava

      def accepts(mediaType: String) = req.accepts(mediaType)

      def cookies = new JCookies {
        def get(name: String): JCookie = {
          req.cookies.get(name).map(makeJavaCookie).orNull
        }

        private def makeJavaCookie(cookie: Cookie): JCookie = {
          new JCookie(cookie.name,
            cookie.value,
            cookie.maxAge.map(i => new Integer(i)).orNull,
            cookie.path,
            cookie.domain.orNull,
            cookie.secure,
            cookie.httpOnly)
        }

        def iterator: java.util.Iterator[JCookie] = {
          req.cookies.toIterator.map(makeJavaCookie).asJava
        }
      }

      override def toString = req.toString

    }
  }

  /**
   * creates a java context from a scala RequestHeader
   * @param request
   */
  def createJavaContext(req: RequestHeader): JContext = {
    createJavaContext(req, createJavaRequest(req))
  }

  /**
   * creates a java context from a scala Request[RequestBody]
   * @param request
   */
  def createJavaContext(req: Request[RequestBody]): JContext = {
    val jreq = new JRequest {

      def uri = req.uri

      def method = req.method

      def version = req.version

      def remoteAddress = req.remoteAddress

      def secure = req.secure

      def host = req.host

      def path = req.path

      def body = req.body

      def headers = req.headers.toMap.map(e => e._1 -> e._2.toArray).asJava

      def acceptLanguages = req.acceptLanguages.map(new play.i18n.Lang(_)).asJava

      def accept = req.accept.asJava

      def acceptedTypes = req.acceptedTypes.asJava

      def accepts(mediaType: String) = req.accepts(mediaType)

      def queryString = {
        req.queryString.mapValues(_.toArray).asJava
      }

      def cookies = new JCookies {
        def get(name: String): JCookie = {
          req.cookies.get(name).map(makeJavaCookie).orNull
        }

        private def makeJavaCookie(cookie: Cookie): JCookie = {
          new JCookie(cookie.name,
            cookie.value,
            cookie.maxAge.map(i => new Integer(i)).orNull,
            cookie.path,
            cookie.domain.orNull,
            cookie.secure,
            cookie.httpOnly)
        }

        def iterator: java.util.Iterator[JCookie] = {
          req.cookies.toIterator.map(makeJavaCookie).asJava
        }
      }

      override def toString = req.toString

    }
    createJavaContext(req, jreq)
  }

  /**
   * creates a java context from a scala RequestHeader and Java Request
   */
  private def createJavaContext(req: RequestHeader, jreq: JRequest): JContext = {
    def lazyCopy[A,B,B1<:B](m: Map[A,B1]) = new F.Function0[JMap[A,B]] {
      def apply: JMap[A,B] = {
        val jm = new JHashMap[A,B]
        for (entry <- m) {
          jm.put(entry._1, entry._2)
        }
        jm
      }
    }

    new JContext(
      req.id,
      req,
      jreq,
      new JResponse(),
      new JSession(lazyCopy(req.session.data)),
      new JFlash(lazyCopy(req.flash.data)),
      new LazyDirtyMap(lazyCopy[String,AnyRef,String](req.tags))
    )
  }

  /**
   * Invoke the given function with the right context set, converting the scala request to a
   * Java request, and converting the resulting Java result to a Scala result, before returning
   * it.
   *
   * This is intended for use by methods in the JavaGlobalSettingsAdapter, which need to be handled
   * like Java actions, but are not Java actions.
   *
   * @param request The request
   * @param f The function to invoke
   * @return The result
   */
  def invokeWithContext(request: RequestHeader, f: JRequest => Option[F.Promise[JSimpleResult]]): Option[Future[SimpleResult]] = {
    val javaContext = createJavaContext(request)
    try {
      JContext.current.set(javaContext)
      f(javaContext.request()).map(_.wrapped.map(createResult(javaContext, _))(trampoline))
    } finally {
      JContext.current.remove()
    }
  }

  /**
   * Creates a partial function from a Java function
   */
  def toPartialFunction[A, B](f: F.Function[A, B]): PartialFunction[A, B] = new PartialFunction[A, B] {
    def apply(a: A) = f.apply(a)
    def isDefinedAt(x: A) = true
  }

}
object JavaHelpers extends JavaHelpers
