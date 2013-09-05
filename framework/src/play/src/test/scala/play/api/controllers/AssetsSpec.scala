package controllers

import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import play.api.mvc.SimpleResult
import play.api.mvc.Results.{ BadRequest, Forbidden }
import play.api.http.Status.{ BAD_REQUEST, FORBIDDEN }

object AssetsSpec extends Specification {

  "Assets controller" should {

    "know how to split strings" in {
      Assets.splitString("", '/') must_== Seq("")
      Assets.splitString("a", '/') must_== Seq("a")
      Assets.splitString("a/b", '/') must_== Seq("a", "b")
      Assets.splitString("a//b", '/') must_== Seq("a", "", "b")
      Assets.splitString("/a/b", '/') must_== Seq("", "a", "b")
      Assets.splitString("/a/b/", '/') must_== Seq("", "a", "b", "")
      Assets.splitString("/abc/xyz", '/') must_== Seq("", "abc", "xyz")
    }

    def beStatus(status: Int) = beLeft.like ({
      case r: SimpleResult => r.header.status must equalTo(status)
    }: PartialFunction[Any, MatchResult[Int]])

    "not look up assets with invalid chars in the URL" in {
      Assets.resourceNameAt("a", "|") must beStatus(BAD_REQUEST)
      Assets.resourceNameAt("a", "hello world") must beStatus(BAD_REQUEST)
      Assets.resourceNameAt("a", "b/[c]/d") must beStatus(BAD_REQUEST)
    }

    "look up assets with the correct resource name" in {
      Assets.resourceNameAt("a", "") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("a", "b") must beRight("/a/b")
      Assets.resourceNameAt("a", "/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("a", "/b") must beRight("/a/b")
      Assets.resourceNameAt("a", "/b/c") must beRight("/a/b/c")
      Assets.resourceNameAt("a", "/b/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a", "") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a", "b") must beRight("/a/b")
      Assets.resourceNameAt("/a", "/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a", "/b") must beRight("/a/b")
      Assets.resourceNameAt("/a", "/b/c") must beRight("/a/b/c")
      Assets.resourceNameAt("/a", "/b/") must beStatus(FORBIDDEN)
    }

    "not treat Windows file separators specially" in {
      Assets.resourceNameAt("a\\z", "") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("a\\z", "b") must beRight("/a\\z/b")
      Assets.resourceNameAt("a\\z", "/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("a\\z", "/b") must beRight("/a\\z/b")
      Assets.resourceNameAt("a\\z", "/b/c") must beRight("/a\\z/b/c")
      Assets.resourceNameAt("a\\z", "/b/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a\\z", "") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a\\z", "b") must beRight("/a\\z/b")
      Assets.resourceNameAt("/a\\z", "/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a\\z", "/b") must beRight("/a\\z/b")
      Assets.resourceNameAt("/a\\z", "/b/c") must beRight("/a\\z/b/c")
      Assets.resourceNameAt("/a\\z", "/b/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("\\a\\z", "") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("\\a\\z", "b") must beRight("/\\a\\z/b")
      Assets.resourceNameAt("\\a\\z", "/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("\\a\\z", "/b") must beRight("/\\a\\z/b")
      Assets.resourceNameAt("\\a\\z", "/b/c") must beRight("/\\a\\z/b/c")
      Assets.resourceNameAt("\\a\\z", "/b/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("x:\\a\\z", "") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("x:\\a\\z", "b") must beRight("/x:\\a\\z/b")
      Assets.resourceNameAt("x:\\a\\z", "/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("x:\\a\\z", "/b") must beRight("/x:\\a\\z/b")
      Assets.resourceNameAt("x:\\a\\z", "/b/c") must beRight("/x:\\a\\z/b/c")
      Assets.resourceNameAt("x:\\a\\z", "/b/") must beStatus(FORBIDDEN)
    }

    "look up assets without percent-decoding the base path" in {
      Assets.resourceNameAt(" ", "x") must beRight("/ /x")
      Assets.resourceNameAt("/1 + 2 = 3", "x") must beRight("/1 + 2 = 3/x")
      Assets.resourceNameAt("/1%20+%202%20=%203", "x") must beRight("/1%20+%202%20=%203/x")
    }

    "look up assets with percent-encoded resource paths" in {
      Assets.resourceNameAt("/x", "/1%20+%202%20=%203") must beRight("/x/1 + 2 = 3")
      Assets.resourceNameAt("/x", "/foo%20bar.txt") must beRight("/x/foo bar.txt")
      Assets.resourceNameAt("/x", "/foo+bar%3A%20baz.txt") must beRight("/x/foo+bar: baz.txt")
    }

    "not look up assets with percent-encoded file separators" in {
      Assets.resourceNameAt("/x", "%2f") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/x", "a%2fb") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/x", "a/%2fb") must beStatus(FORBIDDEN)
    }

    "look up assets even if the file path is a valid URI" in {
      Assets.resourceNameAt("/a", "http://localhost/x") must beRight("/a/http:/localhost/x")
      Assets.resourceNameAt("/a", "//localhost/x") must beRight("/a/localhost/x")
      Assets.resourceNameAt("/a", "../") must beStatus(FORBIDDEN)
    }

    "look up assets with dot-segments in the path" in {
      Assets.resourceNameAt("/a/b", "./c/d") must beRight("/a/b/c/d")
      Assets.resourceNameAt("/a/b", "c/./d") must beRight("/a/b/c/d")
      Assets.resourceNameAt("/a/b", "c/../d") must beRight("/a/b/d")
      Assets.resourceNameAt("/a/b", "c/d/..") must beRight("/a/b/c")
      Assets.resourceNameAt("/a/b", "c/d/../../x") must beRight("/a/b/x")
      // Percent-encoded dot-segments
      Assets.resourceNameAt("/a/b", "%2e/c/d") must beRight("/a/b/c/d")
      Assets.resourceNameAt("/a/b", "c/%2e/d") must beRight("/a/b/c/d")
      Assets.resourceNameAt("/a/b", "c/%2e%2e/d") must beRight("/a/b/d")
      Assets.resourceNameAt("/a/b", "c/d/%2e%2e") must beRight("/a/b/c")
      Assets.resourceNameAt("/a/b", "c/d/%2e%2e/%2e%2e/x") must beRight("/a/b/x")
    }

    "not look up assets with dot-segments that escape the parent path" in {
      Assets.resourceNameAt("/a/b", "..") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "../") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "../c") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "../../c/d") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "../b/c/d") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "../../a/b/c/d") must beStatus(FORBIDDEN)
      // Percent-encoded dot-segments
      Assets.resourceNameAt("/a/b", "%2e%2e") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "%2e%2e/") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "%2e%2e/c") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "%2e%2e/%2e%2e/c/d") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "%2e%2e/b/c/d") must beStatus(FORBIDDEN)
      Assets.resourceNameAt("/a/b", "%2e%2e/%2e%2e/a/b/c/d") must beStatus(FORBIDDEN)
    }

  }
}
