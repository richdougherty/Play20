/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server

import org.jboss.netty.channel._
import org.jboss.netty.bootstrap._
import org.jboss.netty.channel.Channels._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel.group._
import org.jboss.netty.handler.ssl._

import java.net.InetSocketAddress
import javax.net.ssl._
import java.util.concurrent._

import play.core._
import play.api._
import play.core.server.netty._

import java.security.cert.X509Certificate
import java.util.Properties
import scala.util.control.NonFatal
import com.typesafe.netty.http.pipelining.HttpPipeliningHandler
import play.server.SSLEngineProvider

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

/**
 * creates a Server implementation based Netty
 */
class NettyServer(config: ServerConfig) extends Server with ServerWithStop {

  require(config.port.isDefined || config.sslPort.isDefined, "Neither http.port nor https.port is specified")

  def applicationProvider = config.appProvider
  def mode = config.mode

  private def newBootstrap = new ServerBootstrap(
    new org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory(
      Executors.newCachedThreadPool(NamedThreadFactory("netty-boss")),
      Executors.newCachedThreadPool(NamedThreadFactory("netty-worker"))))

  class PlayPipelineFactory(secure: Boolean = false) extends ChannelPipelineFactory {

    def getPipeline = {
      val newPipeline = pipeline()
      if (secure) {
        sslEngineProvider.map { sslEngineProvider =>
          val sslEngine = sslEngineProvider.createSSLEngine()
          sslEngine.setUseClientMode(false)
          newPipeline.addLast("ssl", new SslHandler(sslEngine))
        }
      }
      val maxInitialLineLength = Option(System.getProperty("http.netty.maxInitialLineLength")).map(Integer.parseInt(_)).getOrElse(4096)
      val maxHeaderSize = Option(System.getProperty("http.netty.maxHeaderSize")).map(Integer.parseInt(_)).getOrElse(8192)
      val maxChunkSize = Option(System.getProperty("http.netty.maxChunkSize")).map(Integer.parseInt(_)).getOrElse(8192)
      newPipeline.addLast("decoder", new HttpRequestDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize))
      newPipeline.addLast("encoder", new HttpResponseEncoder())
      newPipeline.addLast("decompressor", new HttpContentDecompressor())
      newPipeline.addLast("http-pipelining", new HttpPipeliningHandler())
      newPipeline.addLast("handler", defaultUpStreamHandler)
      newPipeline
    }

    lazy val sslEngineProvider: Option[SSLEngineProvider] = //the sslContext should be reused on each connection
      try {
        Some(ServerSSLEngine.createSSLEngineProvider(applicationProvider))
      } catch {
        case NonFatal(e) => {
          Play.logger.error(s"cannot load SSL context", e)
          None
        }
      }

  }

  // Keep a reference on all opened channels (useful to close everything properly, especially in DEV mode)
  val allChannels = new DefaultChannelGroup

  // Our upStream handler is stateless. Let's use this instance for every new connection
  val defaultUpStreamHandler = new PlayDefaultUpstreamHandler(this, allChannels)

  // The HTTP server channel
  val HTTP = config.port.map { port =>
    val bootstrap = newBootstrap
    bootstrap.setPipelineFactory(new PlayPipelineFactory)
    val channel = bootstrap.bind(new InetSocketAddress(config.address, port))
    allChannels.add(channel)
    (bootstrap, channel)
  }

  // Maybe the HTTPS server channel
  val HTTPS = config.sslPort.map { port =>
    val bootstrap = newBootstrap
    bootstrap.setPipelineFactory(new PlayPipelineFactory(secure = true))
    val channel = bootstrap.bind(new InetSocketAddress(config.address, port))
    allChannels.add(channel)
    (bootstrap, channel)
  }

  mode match {
    case Mode.Test =>
    case _ => {
      HTTP.foreach { http =>
        Play.logger.info("Listening for HTTP on %s".format(http._2.getLocalAddress))
      }
      HTTPS.foreach { https =>
        Play.logger.info("Listening for HTTPS on port %s".format(https._2.getLocalAddress))
      }
    }
  }

  override def stop() {

    try {
      Play.stop()
    } catch {
      case NonFatal(e) => Play.logger.error("Error while stopping the application", e)
    }

    try {
      super.stop()
    } catch {
      case NonFatal(e) => Play.logger.error("Error while stopping logger", e)
    }

    mode match {
      case Mode.Test =>
      case _ => Play.logger.info("Stopping server...")
    }

    // First, close all opened sockets
    allChannels.close().awaitUninterruptibly()

    // Release the HTTP server
    HTTP.foreach(_._1.releaseExternalResources())

    // Release the HTTPS server if needed
    HTTPS.foreach(_._1.releaseExternalResources())

    mode match {
      case Mode.Dev =>
        Invoker.lazySystem.close()
        Execution.lazyContext.close()
      case _ => ()
    }
  }

  override lazy val mainAddress = {
    if (HTTP.isDefined) {
      HTTP.get._2.getLocalAddress.asInstanceOf[InetSocketAddress]
    } else {
      HTTPS.get._2.getLocalAddress.asInstanceOf[InetSocketAddress]
    }
  }

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
object NettyServer {

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
