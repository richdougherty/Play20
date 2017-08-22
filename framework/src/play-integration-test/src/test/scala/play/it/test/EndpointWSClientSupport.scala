/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.it.test

import java.io.Closeable
import java.util.concurrent.TimeUnit

import akka.actor.{ ActorSystem, Terminated }
import akka.stream.ActorMaterializer
import com.typesafe.sslconfig.ssl.{ SSLConfigSettings, SSLLooseConfig }
import org.specs2.execute.AsResult
import org.specs2.specification.core.Fragment
import play.api.Configuration
import play.api.libs.ws.ahc.{ AhcWSClient, AhcWSClientConfig }
import play.api.libs.ws.{ WSClient, WSClientConfig, WSRequest }
import play.api.mvc.Call

import scala.annotation.implicitNotFound
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

/**
 * Provides a similar interface to [[play.api.test.WsTestClient]], but
 * connects to an integration test's [[ServerEndpoints.ServerEndpoint]] instead of an
 * arbitrary scheme and port.
 */
trait EndpointWSClientSupport {
  self: EndpointIntegrationSpecification =>

  /** Describes a [[WSClient]] that is bound to a particular [[ServerEndpoint]]. */
  @implicitNotFound("Use withAllEndpointWSClients { implicit endpointClient: EndpointWSClient => ... } to get a value")
  trait EndpointWSClient {
    def endpoint: ServerEndpoint
    def client: WSClient
  }

  /** Make a request to the running server endpoint at the given path. */
  def wsUrl(path: String)(implicit endpointClient: EndpointWSClient): WSRequest = {
    endpointClient.client.url(s"${endpointClient.endpoint.scheme}://localhost:" + endpointClient.endpoint.port + path)
  }
  /** Make a request to the running server endpoint using the given call. */
  def wsCall(call: Call)(implicit endpointClient: EndpointWSClient): WSRequest = wsUrl(call.url)

  /**
   * Get the client used to connect to the running server endpoint. This
   * method is provided for compatibility with [[play.test.WSTestClient]].
   * If you don't need compatibility, it's easier to just call `endpointClient.client`
   * instead.
   */
  @deprecated("Use EndpointWSClient.client instead", "2.6.4")
  def withClient[T](block: WSClient => T)(implicit endpointClient: EndpointWSClient): T = block(endpointClient.client)

  /**
   * Takes a [[ServerEndpoint]], creates a matching [[EndpointWSClient]], calls
   * a block of code on the client and then closes the client afterwards.
   *
   * Most users should use [[ApplicationFactoryEndpointWSClientBaker.withAllEndpointWSClients()]]
   * instead of this method.
   */
  def withEndpointWSClient[A](endpoint: ServerEndpoint)(block: EndpointWSClient => A): A = {
    val e = endpoint // Avoid a name clash

    val serverClient = new EndpointWSClient with Closeable {
      override val endpoint = e
      private val actorSystem: ActorSystem = {
        val actorConfig = Configuration(
          "akka.loglevel" -> "WARNING"
        )
        ActorSystem("ServerIntegrationWsTestClient", actorConfig.underlying)
      }
      override val client: WSClient = {
        // Set up custom config to trust any SSL certificate. Unfortunately
        // even though we have the certificate information already loaded
        // we can't easily get it to our WSClient due to limitations in
        // the ssl-config library.
        val sslLooseConfig: SSLLooseConfig = SSLLooseConfig().withAcceptAnyCertificate(true)
        val sslConfig: SSLConfigSettings = SSLConfigSettings().withLoose(sslLooseConfig)
        val wsClientConfig: WSClientConfig = WSClientConfig(ssl = sslConfig)
        val ahcWsClientConfig = AhcWSClientConfig(wsClientConfig = wsClientConfig, maxRequestRetry = 0)

        implicit val materializer = ActorMaterializer(namePrefix = Some("server-ws-client"))(actorSystem)
        AhcWSClient(ahcWsClientConfig)
      }
      override def close(): Unit = {
        client.close()
        val terminated: Future[Terminated] = actorSystem.terminate()
        Await.ready(terminated, Duration(20, TimeUnit.SECONDS))
      }
    }
    try block(serverClient) finally serverClient.close()
  }

  /**
   * Implicit class that enhances [[ApplicationFactory]] with the [[withAllEndpointWSClients()]] method.
   */
  implicit class ApplicationFactoryEndpointWSClientBaker(appFactory: ApplicationFactory) {
    /**
     * Helper that creates a specs2 fragment for the server endpoints given in
     * [[allEndpointRecipes]]. Each fragment creates an application, starts a server,
     * starts a [[WSClient]] and runs the given block of code.
     *
     * {{{
     * withResult(Results.Ok("Hello")) withAllEndpointWSClients {
     *   implicit endpointClient: EndpointWSClient =>
     *     val response = await(wsUrl("/").get())
     *     response.body must_== "Hello"
     * }
     * }}}
     */
    def withAllEndpointWSClients[A: AsResult](block: EndpointWSClient => A): Fragment =
      appFactory.withAllEndpoints { endpoint: ServerEndpoint => withEndpointWSClient(endpoint)(block) }
  }
}
