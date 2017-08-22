/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.it.test

import java.security.KeyStore
import javax.net.ssl._

import play.api.test.PlayRunners
import play.api.{ Application, Configuration, Mode }
import play.core.ApplicationProvider
import play.core.server.ssl.FakeKeyStore
import play.core.server.{ ServerConfig, ServerProvider }
import play.server.api.SSLEngineProvider

trait ServerEndpoints {

  /**
   * Contains information about the port and protocol used to connect to the server.
   * This class is used to abstract out the details of connecting to different backends
   * and protocols. Most tests will operate the same no matter which endpoint they
   * are connected to.
   */
  sealed trait ServerEndpoint {
    def description: String
    def scheme: String
    def port: Int
    def expectedHttpVersions: Set[String]
    def expectedServerAttr: Option[String]
    final override def toString = description
  }
  /** Represents an HTTP connection to a server. */
  trait HttpEndpoint extends ServerEndpoint {
    override def scheme: String = "http"
  }
  /** Represents an HTTPS connection to a server. */
  trait HttpsEndpoint extends ServerEndpoint {
    override def scheme: String = "https"
    /** Information about the server's SSL setup. */
    def serverSsl: ServerSSL
  }

  /** Contains information how SSL is configured for an [[HttpsEndpoint]]. */
  case class ServerSSL(sslContext: SSLContext, trustManager: X509TrustManager)

  /**
   * A recipe for making a [[ServerEndpoint]]. Recipes are often used
   * when describing which tests to run. The recipe can be used to start
   * servers with the correct [[ServerEndpoint]]s.
   *
   * @see [[ServerEndpoints.withEndpoint()]]
   */
  trait ServerEndpointRecipe {
    type EndpointType <: ServerEndpoint
    val description: String
    val configuredHttpPort: Option[Int]
    val configuredHttpsPort: Option[Int]
    def serverConfiguration: Configuration
    def serverProvider: ServerProvider
    def createEndpointFromServer(runningTestServer: play.api.test.TestServer): EndpointType
  }

  /** Provides a recipe for making an [[HttpEndpoint]]. */
  protected class HttpServerEndpointRecipe(
      override val description: String,
      override val serverProvider: ServerProvider,
      extraServerConfiguration: Configuration = Configuration.empty,
      expectedHttpVersions: Set[String],
      expectedServerAttr: Option[String]
  ) extends ServerEndpointRecipe {
    recipe =>
    override type EndpointType = HttpEndpoint
    override val configuredHttpPort: Option[Int] = Some(0)
    override val configuredHttpsPort: Option[Int] = None
    override val serverConfiguration: Configuration = extraServerConfiguration
    override def createEndpointFromServer(runningServer: play.api.test.TestServer): HttpEndpoint = {
      new HttpEndpoint {
        override def description: String = recipe.description
        override def port: Int = runningServer.runningHttpPort.get
        override def expectedHttpVersions: Set[String] = recipe.expectedHttpVersions
        override def expectedServerAttr: Option[String] = recipe.expectedServerAttr
      }
    }
    override def toString: String = s"HttpServerEndpointRecipe($description)"
  }

  /** Provides a recipe for making an [[HttpsEndpoint]]. */
  protected class HttpsServerEndpointRecipe(
      override val description: String,
      override val serverProvider: ServerProvider,
      extraServerConfiguration: Configuration = Configuration.empty,
      expectedHttpVersions: Set[String],
      expectedServerAttr: Option[String]
  ) extends ServerEndpointRecipe {
    recipe =>
    override type EndpointType = HttpsEndpoint
    override val configuredHttpPort: Option[Int] = None
    override val configuredHttpsPort: Option[Int] = Some(0)
    override def serverConfiguration: Configuration = Configuration(
      "play.server.https.engineProvider" -> classOf[ServerEndpoints.SelfSignedSSLEngineProvider].getName
    ) ++ extraServerConfiguration
    override def createEndpointFromServer(runningServer: play.api.test.TestServer): HttpsEndpoint = {
      new HttpsEndpoint {
        override def description: String = recipe.description
        override def port: Int = runningServer.runningHttpsPort.get
        override def expectedHttpVersions: Set[String] = recipe.expectedHttpVersions
        override def expectedServerAttr: Option[String] = recipe.expectedServerAttr
        override val serverSsl: ServerSSL = ServerSSL(
          ServerEndpoints.SelfSigned.sslContext,
          ServerEndpoints.SelfSigned.trustManager
        )
      }
    }
    override def toString: String = s"HttpsServerEndpointRecipe($description)"
  }

  /**
   * Starts a server by following a [[ServerEndpointRecipe]] and using the
   * application provided by an [[ApplicationFactory]]. The server's endpoint
   * is passed to the given `block` of code.
   */
  def withEndpoint[A](endpointRecipe: ServerEndpointRecipe, appFactory: ApplicationFactory)(block: ServerEndpoint => A): A = {
    val application: Application = appFactory.create()

    // Create a ServerConfig with dynamic ports and using a self-signed certificate
    val serverConfig = {
      val sc: ServerConfig = ServerConfig(
        port = endpointRecipe.configuredHttpPort,
        sslPort = endpointRecipe.configuredHttpsPort,
        mode = Mode.Test,
        rootDir = application.path
      )
      val patch = endpointRecipe.serverConfiguration
      sc.copy(configuration = sc.configuration ++ patch)
    }

    // Initialize and start the TestServer
    val testServer: play.api.test.TestServer = new play.api.test.TestServer(
      serverConfig, application, Some(endpointRecipe.serverProvider)
    )
    val runners = new PlayRunners {} // We can't mix in PlayRunners because it pollutes the namespace
    runners.running(testServer) {
      val endpoint: ServerEndpoint = endpointRecipe.createEndpointFromServer(testServer)
      block(endpoint)
    }
  }
}

object ServerEndpoints {

  /**
   * An SSLEngineProvider which simply references the values in the
   * SelfSigned object.
   */
  private[test] class SelfSignedSSLEngineProvider(serverConfig: ServerConfig, appProvider: ApplicationProvider) extends SSLEngineProvider {
    override lazy val createSSLEngine: SSLEngine = SelfSigned.sslContext.createSSLEngine()
  }

  /**
   * Contains a statically initialized self-signed certificate.
   */
  private[test] object SelfSigned {

    /**
     * The SSLContext and TrustManager associated with the self-signed certificate.
     */
    lazy val (sslContext, trustManager): (SSLContext, X509TrustManager) = {
      val keyStore: KeyStore = FakeKeyStore.generateKeyStore

      val kmf: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      kmf.init(keyStore, "".toCharArray)
      val kms: Array[KeyManager] = kmf.getKeyManagers

      val tmf: TrustManagerFactory = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
      tmf.init(keyStore)
      val tms: Array[TrustManager] = tmf.getTrustManagers
      val x509TrustManager: X509TrustManager = tms(0).asInstanceOf[X509TrustManager]

      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(kms, tms, null)

      (sslContext, x509TrustManager)
    }
  }

}