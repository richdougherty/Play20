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
import play.core.server.netty.{ NettyServer => NewNettyServer }

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


// // Simple error monad
// sealed trait Result[+A,+B]
// final case class Success[+B](value: A) extends Result[Nothing,B] {
//   def map[B1>:B](f: B => B1): Result[A,B] = new Success(f(value))
//   def flatMap[A1>:A,B1>:B](f: B => Result[A1,B1]): Result[A1,B1] = f(value)
// }
// final case class Error(message: String) extends Result[Nothing] {
//   def map[B](f: Nothing => B): Result[B] = this
//   def flatMap[B](f: Nothing => Result[B]): Result[B] = this
// }

//class ServerExitException(message: String, returnCode: Int = -1) extends ServerException

trait ServerProcess {
  def args: Seq[String]
  def properties: Properties
  def pid: Option[String]
  def addShutdownHook(hook: => Unit): Unit
  def exit(message: String, cause: Option[Throwable] = None, returnCode: Int = -1): Nothing
}
class RealServerProcess(val args: Seq[String]) extends ServerProcess {
  def properties: Properties = System.getProperties
  def pid: Option[String] = {
    import java.lang.management.ManagementFactory
    ManagementFactory.getRuntimeMXBean.getName.split('@').headOption
  }
  def addShutdownHook(hook: => Unit): Unit = {
    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run() = hook
    })
  }
  def exit(message: String, cause: Option[Throwable] = None, returnCode: Int = -1): Nothing = {
    System.err.println(message)
    cause.foreach(_.printStackTrace())
    System.exit(returnCode)
    throw new Exception("System.exit called") // Code never reached, but gives the method a type of Nothing
  }
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
  def createServer(process: ServerProcess): NewNettyServer = {
    def property(name: String): Option[String] = Option(process.properties.getProperty(name))

    val applicationPath: File = {
      val argumentPath = process.args.headOption
      val propertyPath = property("user.dir")
      val path = argumentPath orElse propertyPath getOrElse process.exit("No application path supplied")
      val file = new File(path)
      if (!(file.exists && file.isDirectory)) {
        process.exit(s"Bad application path: $path")
      }
      file
    }

    // Handle pid file creation and deletion
    {
      val pid = process.pid getOrElse process.exit("Couldn't determine current process's pid")
      val pidFileProperty = property("pidfile.path").map(new File(_))
      val defaultPidFile = new File(applicationPath, "RUNNING_PID")
      val pidFile = (pidFileProperty getOrElse defaultPidFile).getAbsoluteFile

      if (pidFile.getAbsolutePath != "/dev/null") {

        if (pidFile.exists) {
          process.exit(s"This application is already running (Or delete ${pidFile.getPath} file).")
        }

        val out = new FileOutputStream(pidFile)
        try out.write(pid.getBytes) finally out.close() // RICH: This wasn't being closed before, was it intentionally being left open?

        process.addShutdownHook { pidFile.delete() }
      }
    }

    // Parse HTTP config

    val httpPort = property("http.port").fold[Option[Int]](Some(9000)) {
      case "disabled" => None
      case str =>
        val i = try Integer.parseInt(str) catch {
          case _: NumberFormatException => process.exit(s"Invalid HTTP port: $str")
        }
        Some(i)
    }
    val httpsPort = property("https.port").map { str =>
      try Integer.parseInt(str) catch {
          case _: NumberFormatException => process.exit(s"Invalid HTTPS port: $str")
      }
    }
    val address = property("http.address").getOrElse("0.0.0.0")

    val config = ServerConfig(
      new StaticApplication(applicationPath),
      port = httpPort,
      sslPort = httpsPort,
      address = address,
      mode = Mode.Prod,
      properties = process.properties
    )
    val server = new NewNettyServer(config)
    process.addShutdownHook { server.stop() }
    server
  }

  /**
   * attempts to create a NettyServer based on either
   * passed in argument or `user.dir` System property or current directory
   * @param args
   */
  def main(args: Array[String]) {
    val process = new RealServerProcess(args)
    try createServer(process) catch {
      case NonFatal(e) => process.exit("Oops, cannot start the server.", cause = Some(e))
    }
  }

  /**
   * Provides an HTTPS-only NettyServer for the dev environment.
   *
   * <p>This method uses simple Java types so that it can be used with reflection by code
   * compiled with different versions of Scala.
   */
  def mainDevOnlyHttpsMode(buildLink: BuildLink, buildDocHandler: BuildDocHandler, httpsPort: Int): NewNettyServer = {
    mainDev(buildLink, buildDocHandler, None, Some(httpsPort))
  }

  /**
   * Provides an HTTP NettyServer for the dev environment
   *
   * <p>This method uses simple Java types so that it can be used with reflection by code
   * compiled with different versions of Scala.
   */
  def mainDevHttpMode(buildLink: BuildLink, buildDocHandler: BuildDocHandler, httpPort: Int): NewNettyServer = {
    mainDev(buildLink, buildDocHandler, Some(httpPort), Option(System.getProperty("https.port")).map(Integer.parseInt(_)))
  }

  private def mainDev(buildLink: BuildLink, buildDocHandler: BuildDocHandler, httpPort: Option[Int], httpsPort: Option[Int]): NewNettyServer = {
    play.utils.Threads.withContextClassLoader(this.getClass.getClassLoader) {
      try {
        val appProvider = new ReloadableApplication(buildLink, buildDocHandler)
        val config = ServerConfig(appProvider, httpPort,
          httpsPort,
          mode = Mode.Dev,
          properties = System.getProperties)
        new NewNettyServer(config)
      } catch {
        case e: ExceptionInInitializerError => throw e.getCause
      }

    }
  }

}

object NettyServer {
  def main(args: Array[String]) {
    System.err.println(s"play.core.server.NettyServer.main is deprecated. Please use play.core.server.ServerStart.main instead.")
    ServerStart.main(args)
  }
}
