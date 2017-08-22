/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.it.test

import okhttp3.{ OkHttpClient, Request, Response }
import org.specs2.execute.AsResult
import org.specs2.specification.core.Fragment
import play.api.mvc.Call

import scala.annotation.implicitNotFound

/**
 * Provides a similar interface to [[play.api.test.WsTestClient]], but
 * uses OkHttp and connects to an integration test's [[ServerEndpoints.ServerEndpoint]]
 * instead of to an arbitrary scheme and port.
 */
trait EndpointOkHttpClientSupport {
  self: EndpointIntegrationSpecification =>

  /** Describes an [[OkHttpClient] that is bound to a particular [[ServerEndpoint]]. */
  @implicitNotFound("Use withAllEndpointOkHttpClients { implicit endpointClient: EndpointOkHttpClient => ... } to get a value")
  trait EndpointOkHttpClient {
    def endpoint: ServerEndpoint
    def client: OkHttpClient
  }

  /** Make a request to the running server endpoint at the given path. */
  def okUrl(path: String)(implicit endpointClient: EndpointOkHttpClient): Response = {
    val request = new Request.Builder()
      .url(s"${endpointClient.endpoint.scheme}://localhost:${endpointClient.endpoint.port}$path")
      .build()
    endpointClient.client.newCall(request).execute()
  }

  /** Make a request to the running server endpoint using the given call. */
  def okCall(call: Call)(implicit endpointClient: EndpointOkHttpClient): Response = okUrl(call.url)

  /**
   * Takes a [[ServerEndpoint]], creates a matching [[EndpointOkHttpClient]], calls
   * a block of code on the client and then closes the client afterwards.
   *
   * Most users should use [[ApplicationFactoryEndpointOkHttpClientBaker.withAllEndpointOkHttpClients()]]
   * instead of this method.
   */
  def withEndpointOkHttpClient[A](endpoint: ServerEndpoint)(block: EndpointOkHttpClient => A): A = {
    val e = endpoint // Avoid a name clash
    val serverClient = new EndpointOkHttpClient {
      override val endpoint = e
      override val client: OkHttpClient = {
        endpoint match {
          case e: HttpsEndpoint =>
            // Create a client that trusts the server's certificate
            new OkHttpClient.Builder()
              .sslSocketFactory(e.serverSsl.sslContext.getSocketFactory, e.serverSsl.trustManager)
              .build()
          case _ => new OkHttpClient()
        }
      }
    }
    block(serverClient)
  }

  /**
   * Implicit class that enhances [[ApplicationFactory]] with the [[withAllEndpointOkHttpClients()]] method.
   */
  implicit class ApplicationFactoryEndpointOkHttpClientBaker(appFactory: ApplicationFactory) {
    /**
     * Helper that creates a specs2 fragment for the server endpoints given in
     * [[allEndpointRecipes]]. Each fragment creates an application, starts a server,
     * starts an [[OkHttpClient]] and runs the given block of code.
     *
     * {{{
     * withResult(Results.Ok("Hello")) withAllEndpointOkHttpClients {
     *   implicit endpointClient: EndpointOkHttpClient =>
     *     val response = okUrl("/")
     *     response.body.string must_== "Hello"
     * }
     * }}}
     */
    def withAllEndpointOkHttpClients[A: AsResult](block: EndpointOkHttpClient => A): Fragment =
      appFactory.withAllEndpoints { endpoint: ServerEndpoint => withEndpointOkHttpClient(endpoint)(block) }
  }

}
