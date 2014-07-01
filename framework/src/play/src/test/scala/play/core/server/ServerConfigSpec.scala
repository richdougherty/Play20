/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server

import java.util.Properties
import org.specs2.mutable.Specification
import play.core.ApplicationProvider

object ServerConfigSpec extends Specification {

  class Fake extends ApplicationProvider {
    def path = ???
    def get = ???
  }

  "ServerConfig" should {
    "fail when both http and https ports are missing" in {
      ServerConfig(
        new Fake,
        port = None,
        sslPort = None,
        properties = new Properties()
      ) must throwAn[IllegalArgumentException]
    }
  }


}
