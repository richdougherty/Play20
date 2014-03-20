/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.mvc

import java.util.{HashMap => JHashMap, Map => JMap}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{ Arbitrary, Gen }
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck

object SessionSpec extends Specification {

  "Session" should {

    "copy its contents from a map" in {
      val m = new JHashMap[String,String]()
      val s = new Http.Session(m)
      m.isEmpty must beTrue
      s.isEmpty must beTrue
      m.put("a","A")
      m.isEmpty must beFalse
      s.isEmpty must beTrue
    }

    "be clean when created" in {
      val m = new JHashMap[String,String]()
      val s = new Http.Session(m)
      s.isDirty must beFalse
    }

    "support remove" in {
      val m = new JHashMap[String,String]()
      m.put("a", "A")
      val s = new Http.Session(m)
      m.get("a") must_== "A"
      s.get("a") must_== "A"
      s.isDirty must beFalse
      s.remove("a")
      m.get("a") must_== "A"
      s.get("a") must beNull
      s.isDirty must beTrue
    }

    "support put" in {
      val m = new JHashMap[String,String]()
      val s = new Http.Session(m)
      m.isEmpty must beTrue
      s.isEmpty must beTrue
      s.isDirty must beFalse
      s.put("a", "A")
      m.isEmpty must beTrue
      s.isEmpty must beFalse
      s.isDirty must beTrue
      s.get("a") must_== "A"
    }

    "support putAll" in {
      val m = new JHashMap[String,String]()
      val s = new Http.Session(m)
      m.isEmpty must beTrue
      s.isEmpty must beTrue
      s.isDirty must beFalse
      s.putAll {
        val m2 = new JHashMap[String,String]()
        m2.put("b", "B")
        m2.put("c", "C")
        m2
      }
      m.isEmpty must beTrue
      s.isEmpty must beFalse
      s.isDirty must beTrue
      s.get("b") must_== "B"
      s.get("c") must_== "C"
    }

    "support clear" in {
      val m = new JHashMap[String,String]()
      m.put("a", "A")
      val s = new Http.Session(m)
      m.get("a") must_== "A"
      s.get("a") must_== "A"
      s.isDirty must beFalse
      s.clear()
      m.get("a") must_== "A"
      s.get("a") must beNull
      s.isDirty must beTrue
    }

  }

}