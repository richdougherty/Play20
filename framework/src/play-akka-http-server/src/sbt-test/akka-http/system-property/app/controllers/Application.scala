/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package controllers

import play.api.mvc._

object Application extends Controller {
  def index = Action { request =>
    val httpServerTag = request.tags.getOrElse("HTTP_SERVER", "unknown")
    Ok(s"HTTP_SERVER tag: $httpServerTag")
  }
}