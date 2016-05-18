/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package play.http;

import play.mvc.Http.RequestHeader;
import play.mvc.Http.Status;
import play.mvc.Result;

import java.util.concurrent.CompletionStage;

/**
 * Component for handling HTTP errors in Play. An instance of this class is
 * called by Play when an error occurs. The handler generates a [[Result]]
 * to send. For example, an error page may be displayed. The handler may
 * optionally perform other tasks, such as logging a record of the error.
 *
 * @since 2.4.0
 */
public interface HttpErrorHandler {

    /**
     * Invoked when a client error occurs, that is, an error in the 4xx series.
     *
     * @param request The request that caused the client error.
     * @param statusCode The error status code.  Must be greater or equal to 400, and less than 500.
     * @param message The error message.
     */
    CompletionStage<Result> onClientError(RequestHeader request, int statusCode, String message);
    
    /**
     * Invoked when a server error occurs.
     *
     * @param request The request that triggered the server error.
     * @param statusCode The error status code.  Must be greater or equal to 500, and less than 600.
     * @param exception The server error.
     */
    CompletionStage<Result> onServerError(RequestHeader request, int statusCode, Throwable exception);

    /**
     * This method is no longer invoked by Play; override the version with the
     * statusCode parameter instead.
     *
     * @param request The request that triggered the server error.
     * @param exception The server error.
     * @deprecated Use the version of this method with the statusCode parameter.
     */
    @Deprecated
    default CompletionStage<Result> onServerError(RequestHeader request, Throwable exception) {
        return onServerError(request, Status.INTERNAL_SERVER_ERROR, exception);
    }
}
