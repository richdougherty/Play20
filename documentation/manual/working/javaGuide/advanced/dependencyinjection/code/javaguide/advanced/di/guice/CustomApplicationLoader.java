/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package javaguide.advanced.di.guice;

//#custom-application-loader
import play.api.Application;
import play.api.ApplicationLoader;
import play.api.inject.guice.GuiceApplicationBuilder;
import play.api.inject.guice.GuiceApplicationLoader;
import play.libs.Scala;

public class CustomApplicationLoader extends GuiceApplicationLoader {

  @Override
  public GuiceApplicationBuilder build(ApplicationLoader.Context context) {
    return new GuiceApplicationBuilder()
        .in(context.environment)
        .loadConfig(context.initialConfiguration)
        .overrides(Scala.asList(overrides(context)).toArray());
  }

}
//#custom-application-loader
