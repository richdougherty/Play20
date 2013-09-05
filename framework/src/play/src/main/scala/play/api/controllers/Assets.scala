package controllers

import play.api._
import play.api.mvc._
import play.api.libs._
import play.api.libs.iteratee._
import Play.current
import java.io._
import java.net.{ URI, JarURLConnection }
import org.joda.time.format.{ DateTimeFormatter, DateTimeFormat }
import org.joda.time.DateTimeZone
import collection.JavaConverters._
import scala.util.control.NonFatal
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.utils.UriEncoding
import scala.collection.mutable.ArrayBuffer
import play.utils.InvalidUriEncodingException

/**
 * Controller that serves static resources.
 *
 * Resources are searched in the classpath.
 *
 * It handles Last-Modified and ETag header automatically.
 * If a gzipped version of a resource is found (Same resource name with the .gz suffix), it is served instead.
 *
 * You can set a custom Cache directive for a particular resource if needed. For example in your application.conf file:
 *
 * {{{
 * "assets.cache./public/images/logo.png" = "max-age=3600"
 * }}}
 *
 * You can use this controller in any application, just by declaring the appropriate route. For example:
 * {{{
 * GET     /assets/\uFEFF*file               controllers.Assets.at(path="/public", file)
 * }}}
 */
object Assets extends AssetsBuilder

class AssetsBuilder extends Controller {

  private val timeZoneCode = "GMT"

  //Dateformatter is immutable and threadsafe
  private val df: DateTimeFormatter =
    DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss '" + timeZoneCode + "'").withLocale(java.util.Locale.ENGLISH).withZone(DateTimeZone.forID(timeZoneCode))

  //Dateformatter is immutable and threadsafe
  private val dfp: DateTimeFormatter =
    DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss").withLocale(java.util.Locale.ENGLISH).withZone(DateTimeZone.forID(timeZoneCode))

  private val parsableTimezoneCode = " " + timeZoneCode

  private lazy val defaultCharSet = Play.configuration.getString("default.charset").getOrElse("utf-8")

  private def addCharsetIfNeeded(mimeType: String): String =
    if (MimeTypes.isText(mimeType))
      "; charset=" + defaultCharSet
    else ""

  /**
   * Generates an `Action` that serves a static resource. If an illegal path is requested
   * a Forbidden result is returned. If the file doesn't exist then NotFound is returned.
   *
   * @param path A reference to the location on the classpath that holds the static resources,
   * e.g. "/public". Because this is a classpath location, directories must be separated by `/`
   * characters, even if the platform file separator is different. This is parameter is
   * not URL encoded.
   * @param file The location of the static resource within the path. This parameter must be
   * URL encoded, i.e. characters such as space must be percent encoded. Dot-segments
   * such as ".." and "." are handled. A ".." segment will be normalized to its parent directory, but it
   * is not permitted to escape the classpath that holds the static resources. For safety,
   * embedded / characters, encoded as %2f, are not permitted either. If a location ends in /
   * then it is forbidden, as it can never resolve to a resource.
   */
  def at(path: String, file: String): Action[AnyContent] = Action { implicit request =>
    resourceNameAt(path, file).right.map(findResource(_).getOrElse(NotFound)).merge
  }

  /**
   * Get the classpath-relative path to a static resource from a root path and a
   * URL encoded relative path.
   */
  private[controllers] def resourceNameAt(path: String, file: String): Either[Status, String] = {
    val decodedFilePath: Either[Status, Seq[String]] = try {
      Right(splitString(file, '/').map(UriEncoding.decodePathSegment(_, "utf-8")))
    } catch {
      case _: InvalidUriEncodingException => Left(BadRequest)
    }

    val normalizedFilePath: Either[Status, List[String]] = decodedFilePath.right.flatMap {
      _.foldLeft[Either[Status, List[String]]](Right(Nil)) {
        case (Left(result), _) =>
          // Path was invalid on an earlier segment, it remains invalid
          Left(result)
        case (Right(Nil), "..") =>
          // It is invalid to follow a ".." path segment when we're at the root of the hierarchy
          Left(Forbidden)
        case (Right(_ :: parentSegments), "..") =>
          // A path segment of ".." removes the most recent path
          Right(parentSegments)
        case (Right(segments), ".") =>
          // A path segment of "." can be ignored as it doesn't modify the path
          Right(segments)
        case (Right(_), segment) if segment.contains("/") =>
          // Mark the path as invalid if a decoded path segment contains a "/" character
          Left(Forbidden)
        case (Right("" :: segments), segment) =>
          // Get rid of empty path segments when they occur in the middle of a path
          // (but leave them if they're at the beginning or the end)
          Right(segment :: segments)
        case (Right(segments), segment) =>
          // Add normal path segments to the list of path segments that have been built up
          Right(segment :: segments)
      }.right.map(_.reverse)
    }

    normalizedFilePath.right.map { normalized =>
      val sb = new StringBuilder
      if (!path.startsWith("/")) sb.append('/') // Fix up root path if necessary
      sb.append(path)
      sb.append('/')
      normalized.addString(sb, "", "/", "")
      sb.toString
    }.right.flatMap { name =>
      if (name.endsWith("/")) Left(Forbidden) else Right(name)
    }
  }

  /**
   * Split a string on a character. Similar to `String.split` except, for this method,
   * the invariant {{{splitString(s, '/').mkString("/") == s}}} holds.
   *
   * For example:
   * {{{
   * splitString("//a//", '/') == Seq("", "", "a", "", "")
   * String.split("//a//", '/') == Seq("", "", "a")
   * }}}
   */
  private[controllers] def splitString(s: String, c: Char): Seq[String] = {
    val result = scala.collection.mutable.ListBuffer.empty[String]
    import scala.annotation.tailrec
    @tailrec
    def splitLoop(start: Int): Unit = if (start < s.length) {
      var end = s.indexOf(c, start)
      if (end == -1) {
        result += s.substring(start)
      } else {
        result += s.substring(start, end)
        splitLoop(end + 1)
      }
    } else if (start == s.length) {
      result += ""
    }
    splitLoop(0)
    result
  }

  /**
   * Given a class-path relative path to a resource, serve it up. Returns None if the
   * resource can't be found.
   */
  private def findResource(resourceName: String)(implicit request: Request[_]): Option[Result] = {
    val gzippedResource = Play.resource(resourceName + ".gz")

    val resource = {
      gzippedResource.map(_ -> true)
        .filter(_ => request.headers.get(ACCEPT_ENCODING).map(_.split(',').exists(_.trim == "gzip" && Play.isProd)).getOrElse(false))
        .orElse(Play.resource(resourceName).map(_ -> false))
    }

    def maybeNotModified(url: java.net.URL) = {
      def parseDate(date: String): Option[java.util.Date] = try {
        //jodatime does not parse timezones, so we handle that manually
        val d = dfp.parseDateTime(date.replace(parsableTimezoneCode, "")).toDate
        Some(d)
      } catch {
        case NonFatal(_) => None
      }

      // First check etag. Important, if there is an If-None-Match header, we MUST not check the
      // If-Modified-Since header, regardless of whether If-None-Match matches or not. This is in
      // accordance with section 14.26 of RFC2616.
      request.headers.get(IF_NONE_MATCH) match {
        case Some(etags) => {
          etagFor(url).filter(etag =>
            etags.split(",").exists(_.trim == etag)
          ).map(_ => cacheableResult(url, NotModified))
        }
        case None => {
          request.headers.get(IF_MODIFIED_SINCE).flatMap(parseDate).flatMap { ifModifiedSince =>
            lastModifiedFor(url).flatMap(parseDate).filterNot(lastModified => lastModified.after(ifModifiedSince))
          }.map(_ => NotModified.withHeaders(
            DATE -> df.print({ new java.util.Date }.getTime)))
        }
      }
    }

    def cacheableResult[A <: Result](url: java.net.URL, r: A) = {
      // Add Etag if we are able to compute it
      val taggedResponse = etagFor(url).map(etag => r.withHeaders(ETAG -> etag)).getOrElse(r)
      val lastModifiedResponse = lastModifiedFor(url).map(lastModified => taggedResponse.withHeaders(LAST_MODIFIED -> lastModified)).getOrElse(taggedResponse)

      // Add Cache directive if configured
      val cachedResponse = lastModifiedResponse.withHeaders(CACHE_CONTROL -> {
        Play.configuration.getString("\"assets.cache." + resourceName + "\"").getOrElse(Play.mode match {
          case Mode.Prod => Play.configuration.getString("assets.defaultCache").getOrElse("max-age=3600")
          case _ => "no-cache"
        })
      })
      cachedResponse
    }

    resource.map {

      case (url, _) if new File(url.getFile).isDirectory => NotFound

      case (url, isGzipped) => {

        lazy val (length, resourceData) = {
          val stream = url.openStream()
          try {
            (stream.available, Enumerator.fromStream(stream))
          } catch {
            case _: Throwable => (-1, Enumerator[Array[Byte]]())
          }
        }

        if (length == -1) {
          NotFound
        } else {
          maybeNotModified(url).getOrElse {
            // Prepare a streamed response
            val response = SimpleResult(
              ResponseHeader(OK, Map(
                CONTENT_LENGTH -> length.toString,
                CONTENT_TYPE -> MimeTypes.forFileName(resourceName).map(m => m + addCharsetIfNeeded(m)).getOrElse(BINARY),
                DATE -> df.print({ new java.util.Date }.getTime))),
              resourceData)

            // If there is a gzipped version, even if the client isn't accepting gzip, we need to specify the
            // Vary header so proxy servers will cache both the gzip and the non gzipped version
            val gzippedResponse = (gzippedResource.isDefined, isGzipped) match {
              case (true, true) => response.withHeaders(VARY -> ACCEPT_ENCODING, CONTENT_ENCODING -> "gzip")
              case (true, false) => response.withHeaders(VARY -> ACCEPT_ENCODING)
              case _ => response
            }
            cacheableResult(url, gzippedResponse)
          }
        }

      }

    }

  }

  // -- LastModified handling

  private val lastModifieds = (new java.util.concurrent.ConcurrentHashMap[String, String]()).asScala

  private def lastModifiedFor(resource: java.net.URL): Option[String] = {
    def formatLastModified(lastModified: Long): String = df.print(lastModified)

    def maybeLastModified(resource: java.net.URL): Option[Long] = {
      resource.getProtocol match {
        case "file" => Some(new File(resource.getPath).lastModified)
        case "jar" => {
          Option(resource.openConnection)
            .map(_.asInstanceOf[JarURLConnection].getJarEntry.getTime)
            .filterNot(_ == -1)
        }
        case _ => None
      }
    }

    def cachedLastModified(resource: java.net.URL)(orElseAction: => Option[String]): Option[String] =
      lastModifieds.get(resource.toExternalForm).orElse(orElseAction)

    def setAndReturnLastModified(resource: java.net.URL): Option[String] = {
      val mlm = maybeLastModified(resource).map(formatLastModified)
      mlm.foreach(lastModifieds.put(resource.toExternalForm, _))
      mlm
    }

    if (Play.isProd) cachedLastModified(resource) { setAndReturnLastModified(resource) }
    else setAndReturnLastModified(resource)
  }

  // -- ETags handling

  private val etags = (new java.util.concurrent.ConcurrentHashMap[String, String]()).asScala

  private def etagFor(resource: java.net.URL): Option[String] = {
    etags.get(resource.toExternalForm).filter(_ => Play.isProd).orElse {
      val maybeEtag = lastModifiedFor(resource).map(_ + " -> " + resource.toExternalForm).map("\"" + Codecs.sha1(_) + "\"")
      maybeEtag.foreach(etags.put(resource.toExternalForm, _))
      maybeEtag
    }
  }

}

