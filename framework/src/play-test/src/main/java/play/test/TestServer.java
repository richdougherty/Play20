/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.test;

import play.core.server.NettyServer;
import scala.None$;
import scala.Option;
import scala.Some;

/**
 * A test Netty web server.
 */
public class TestServer extends play.api.test.TestServer {

    /**
     * A test Netty web server.
     *
     * @param port HTTP port to bind on.
     * @param application The FakeApplication to load in this server.
     */
    @SuppressWarnings("unchecked")
    public TestServer(int port, FakeApplication application) {
        super(port, application.getWrappedApplication(), (Option) None$.MODULE$, NettyServer.defaultServerProvider());
    }

    /**
     * A test Netty web server with HTTPS support
     * @param port HTTP port to bind on
     * @param application The FakeApplication to load in this server
     * @param sslPort HTTPS port to bind on
     */
    @SuppressWarnings("unchecked")
    public TestServer(int port, FakeApplication application, int sslPort) {
        super(port, application.getWrappedApplication(), new Some(sslPort), NettyServer.defaultServerProvider());
    }

}