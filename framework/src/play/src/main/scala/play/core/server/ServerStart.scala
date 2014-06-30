/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server

// import org.jboss.netty.channel._
// import org.jboss.netty.bootstrap._
// import org.jboss.netty.channel.Channels._
// import org.jboss.netty.handler.codec.http._
// import org.jboss.netty.channel.group._
// import org.jboss.netty.handler.ssl._

import java.net.InetSocketAddress
import javax.net.ssl._
// import java.util.concurrent._

import play.core._
import play.api._
// import play.core.server.netty._

import java.security.cert.X509Certificate
import java.util.Properties
import scala.util.control.NonFatal
// import com.typesafe.netty.http.pipelining.HttpPipeliningHandler
// import play.server.SSLEngineProvider
import play.core.server.netty.NettyServer

case class ServerConfig(
  appProvider: ApplicationProvider,
  port: Option[Int],
  sslPort: Option[Int] = None,
  address: String = "0.0.0.0",
  mode: Mode.Mode = Mode.Prod,
  properties: Properties // TODO: Enumerate individual config settings
)

/**
 * provides a stopable Server
 */
trait ServerWithStop {
  def stop(): Unit
  def mainAddress: InetSocketAddress
}



object noCATrustManager extends X509TrustManager {
  val nullArray = Array[X509Certificate]()
  def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String) {}
  def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String) {}
  def getAcceptedIssuers() = nullArray
}

/**
 * bootstraps Play application with a NettyServer backened
 */
object ServerStart {

  import java.io._

  /**
   * creates a NettyServer based on the application represented by applicationPath
   * @param applicationPath path to application
   */
  def createServer(applicationPath: File): Option[NettyServer] = {
    // Manage RUNNING_PID file
    java.lang.management.ManagementFactory.getRuntimeMXBean.getName.split('@').headOption.map { pid =>
      val pidFile = Option(System.getProperty("pidfile.path")).map(new File(_)).getOrElse(new File(applicationPath.getAbsolutePath, "RUNNING_PID"))

      // The Logger is not initialized yet, we print the Process ID on STDOUT
      println("Play server process ID is " + pid)

      if (pidFile.getAbsolutePath != "/dev/null") {
        if (pidFile.exists) {
          println("This application is already running (Or delete " + pidFile.getAbsolutePath + " file).")
          System.exit(-1)
        }

        new FileOutputStream(pidFile).write(pid.getBytes)
        Runtime.getRuntime.addShutdownHook(new Thread {
          override def run {
            pidFile.delete()
          }
        })
      }
    }

    try {
      val properties = System.getProperties()
      val config = ServerConfig(
        new StaticApplication(applicationPath),
        Option(properties.getProperty("http.port")).fold(Option(9000))(p => if (p == "disabled") Option.empty[Int] else Option(Integer.parseInt(p))),
        Option(properties.getProperty("https.port")).map(Integer.parseInt(_)),
        Option(properties.getProperty("http.address")).getOrElse("0.0.0.0"),
        properties = properties
      )
      val server = new NettyServer(config)

      Runtime.getRuntime.addShutdownHook(new Thread {
        override def run {
          server.stop()
        }
      })

      Some(server)
    } catch {
      case NonFatal(e) => {
        println("Oops, cannot start the server.")
        e.printStackTrace()
        None
      }
    }

  }

  /**
   * attempts to create a NettyServer based on either
   * passed in argument or `user.dir` System property or current directory
   * @param args
   */
  def main(args: Array[String]) {
    args.headOption
      .orElse(Option(System.getProperty("user.dir")))
      .map { applicationPath =>
        val applicationFile = new File(applicationPath)
        if (!(applicationFile.exists && applicationFile.isDirectory)) {
          println("Bad application path: " + applicationPath)
        } else {
          createServer(applicationFile).getOrElse(System.exit(-1))
        }
      }.getOrElse {
        println("No application path supplied")
      }
  }

  /**
   * Provides an HTTPS-only NettyServer for the dev environment.
   *
   * <p>This method uses simple Java types so that it can be used with reflection by code
   * compiled with different versions of Scala.
   */
  def mainDevOnlyHttpsMode(buildLink: BuildLink, buildDocHandler: BuildDocHandler, httpsPort: Int): NettyServer = {
    mainDev(buildLink, buildDocHandler, None, Some(httpsPort))
  }

  /**
   * Provides an HTTP NettyServer for the dev environment
   *
   * <p>This method uses simple Java types so that it can be used with reflection by code
   * compiled with different versions of Scala.
   */
  def mainDevHttpMode(buildLink: BuildLink, buildDocHandler: BuildDocHandler, httpPort: Int): NettyServer = {
    mainDev(buildLink, buildDocHandler, Some(httpPort), Option(System.getProperty("https.port")).map(Integer.parseInt(_)))
  }

  private def mainDev(buildLink: BuildLink, buildDocHandler: BuildDocHandler, httpPort: Option[Int], httpsPort: Option[Int]): NettyServer = {
    play.utils.Threads.withContextClassLoader(this.getClass.getClassLoader) {
      try {
        val appProvider = new ReloadableApplication(buildLink, buildDocHandler)
        val config = ServerConfig(appProvider, httpPort,
          httpsPort,
          mode = Mode.Dev,
          properties = System.getProperties)
        new NettyServer(config)
      } catch {
        case e: ExceptionInInitializerError => throw e.getCause
      }

    }
  }

}

// object NettyServer {
//   def main(args: Array[String]) {
//     System.err.println(s"play.core.server.NettyServer.main is deprecated. Please use ServerStart.main instead.")
//     ServerStart.main(args)
//   }
// }
