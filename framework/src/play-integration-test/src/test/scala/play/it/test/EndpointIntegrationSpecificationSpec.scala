/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.it.test

import okhttp3.{ Protocol, Response }
import play.api.mvc._
import play.api.mvc.request.RequestAttrKey
import play.api.test.PlaySpecification

/**
 * Tests that the [[EndpointIntegrationSpecification]] works properly.
 */
class EndpointIntegrationSpecificationSpec extends PlaySpecification with AllEndpointsIntegrationSpecification with EndpointOkHttpClientSupport {

  "Endpoints" should {
    "respond with the highest supported HTTP protocol" in {
      withResult(Results.Ok("Hello")) withAllEndpointOkHttpClients { implicit endpointClient: EndpointOkHttpClient =>
        val response: Response = okUrl("/")
        val protocol = response.protocol
        if (endpointClient.endpoint.expectedHttpVersions.contains("2")) {
          protocol must_== Protocol.HTTP_2
        } else if (endpointClient.endpoint.expectedHttpVersions.contains("1.1")) {
          protocol must_== Protocol.HTTP_1_1
        } else {
          ko("All endpoints should support at least HTTP/1.1")
        }
        response.body.string must_== "Hello"
      }
    }
    "respond with the correct server attribute" in withAction { Action: DefaultActionBuilder =>
      Action { request: Request[_] =>
        Results.Ok(request.attrs.get(RequestAttrKey.Server).toString)
      }
    }.withAllEndpointOkHttpClients { implicit endpointClient: EndpointOkHttpClient =>
      val response: Response = okUrl("/")
      response.body.string must_== endpointClient.endpoint.expectedServerAttr.toString
    }
  }
}