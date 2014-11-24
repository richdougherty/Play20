/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.buildlink.application;

/**
 * A server that can be run during dev mode.
 */
public interface DevModeServer {

    /**
     * Stop the server.
     */
	public void stop();

    /**
     * Get the address of the server.
     *
     * @return The address of the server.
     */
	public java.net.InetSocketAddress mainAddress(); 

}