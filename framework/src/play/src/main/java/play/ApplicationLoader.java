/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package play;

import java.util.Collections;
import java.util.Map;
import play.core.SourceMapper;
import play.core.WebCommands;
import play.libs.Scala;

/**
 * A Play application.
 *
 * Application creation is handled by the framework engine.
 */
public interface ApplicationLoader {

  /**
   * Load an application given the context.
   */
  Application load(ApplicationLoader.Context context);

  final static class Context {
    private final play.api.ApplicationLoader.Context underlying;
    private final Configuration initialConfiguration;
    private final Environment environment;

    public Context(play.api.ApplicationLoader.Context underlying) {
        this.underlying = underlying;
        this.environment = new Environment(underlying.environment());
        this.initialConfiguration = new Configuration(underlying.initialConfiguration());
    }

    public play.api.ApplicationLoader.Context underlying() {
        return underlying;
    }

    public Environment environment() {
        return environment;
    }

    public Configuration initialConfiguration() {
        return initialConfiguration;
    }
  }

 static Context createContext(Environment environment, Map<String, String> initialConfiguration) {
    play.api.ApplicationLoader.Context scalaContext = play.api.ApplicationLoader$.MODULE$.createContext(
        environment.underlying(),
        Scala.asScala(initialConfiguration),
        Scala.<play.core.SourceMapper>None(),
        new play.core.DefaultWebCommands());
    return new Context(scalaContext);
 }

 static Context createContext(Environment environment) {
    return createContext(environment, Collections.emptyMap());
 }

}
