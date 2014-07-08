/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server

import com.typesafe.netty.http.pipelining.HttpPipeliningHandler
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.jboss.netty.bootstrap._
import org.jboss.netty.channel._
import org.jboss.netty.channel.Channels._
import org.jboss.netty.channel.group._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.ssl._
import play.api._
import play.core._
import play.core.server.netty._
import play.server.SSLEngineProvider
import scala.util.control.NonFatal

/**
 * creates a Server implementation based Netty
 */
class NettyServer(config: ServerConfig, appProvider: ApplicationProvider) extends Server with ServerWithStop {

  def applicationProvider = appProvider
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
      def getIntProperty(name: String, default: Int): Int = {
        Option(config.properties.getProperty(name)).map(Integer.parseInt(_)).getOrElse(default)
      }
      val maxInitialLineLength = getIntProperty("http.netty.maxInitialLineLength", 4096)
      val maxHeaderSize = getIntProperty("http.netty.maxHeaderSize", 8192)
      val maxChunkSize = getIntProperty("http.netty.maxChunkSize", 8192)
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

/**
 * Bootstraps Play application with a NettyServer backend.
 */
object NettyServer extends ServerStart {

  val defaultServerProvider = new ServerProvider {
    def createServer(config: ServerConfig, appProvider: ApplicationProvider) = new NettyServer(config, appProvider)
  }

}