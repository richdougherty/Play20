/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package play.inject.guice;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.List;
import play.api.inject.guice.GuiceableModule;
// import play.Application;
import play.Configuration;
// import play.core.j.JavaGlobalSettingsAdapter;
import play.Environment;
import play.GlobalSettings;
import play.libs.Scala;

import static scala.compat.java8.JFunction.func;

import play.Application;
import play.ApplicationLoader;

/*

import play.api.{ Application, ApplicationLoader, Configuration, Environment, OptionalSourceMapper }
import play.api.inject.{ bind, Injector => PlayInjector }
import play.core.WebCommands

class GuiceApplicationLoader(builder: GuiceApplicationBuilder) extends ApplicationLoader {

  // empty constructor needed for instantiating via reflection
  def this() = this(new GuiceApplicationBuilder)

  def load(context: ApplicationLoader.Context): Application = {
    builder(context).build
  }

  def builder(context: ApplicationLoader.Context): GuiceApplicationBuilder = {
    builder
      .in(context.environment)
      .loadConfig(context.initialConfiguration)
      .overrides(overrides(context): _*)
  }

  def overrides(context: ApplicationLoader.Context): Seq[GuiceableModule] = {
    Seq(
      bind[OptionalSourceMapper] to new OptionalSourceMapper(context.sourceMapper),
      bind[WebCommands] to context.webCommands)
  }

}
*/

/**
 * An ApplicationLoader that uses Guice to bootstrap the application.
 */
public class GuiceApplicationLoader implements ApplicationLoader {

    private final GuiceApplicationBuilder builder;

    public GuiceApplicationLoader() {
        this(new GuiceApplicationBuilder());
    }

    public GuiceApplicationLoader(GuiceApplicationBuilder builder) {
        this.builder = builder;
    }

    @Override
    public Application load(ApplicationLoader.Context context) {
        return builder(context).build();
    }

    protected GuiceApplicationBuilder builder(ApplicationLoader.Context context) {
        return builder
            .in(context.environment())
            .loadConfig(context.initialConfiguration())
            .overrides(overrides(context));
    }

    protected GuiceableModule[] overrides(ApplicationLoader.Context context) {
        // Get the default GuiceableModule list from the Scala GuiceApplicationLoader object
        scala.reflect.ClassTag guiceableModuleTag = scala.reflect.ClassTag$.MODULE$.apply(GuiceableModule.class);
        scala.collection.Seq<GuiceableModule> seq = play.api.inject.guice.GuiceApplicationLoader$.MODULE$.defaultOverrides(context.underlying());
        GuiceableModule[] arr = new GuiceableModule[seq.length()];
        seq.copyToArray(arr);
        return arr;
    }

}
