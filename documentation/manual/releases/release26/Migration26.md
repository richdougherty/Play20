## Changes to `HttpErrorHandler`

The `HttpErrorHandler` interface now accepts a status code for server errors, instead of assuming all errors are 500 server errors. This allows other [5xx errors](https://en.wikipedia.org/wiki/List_of_HTTP_status_codes#5xx_Server_Error) to be rendered.

### How to migrate

If you implement `HttpErrorHandler` you should change your `onServerError` method to take a new status code parameter.

```java
@Override
public CompletionStage<Result> onServerError(RequestHeader request, int statusCode, Throwable exception)
```

```scala
override def onServerError(request: RequestHeader, statusCode: Int, exception: Throwable): Future[Result]
```

If you extend `DefaultHttpErrorHandler` you should change your `onDevServerError` and `onProdServerError` methods to take the new status code parameter.

```java
@Override
public CompletionStage<Result> onDevServerError(RequestHeader request, int statusCode, Throwable exception)
@Override
public CompletionStage<Result> onProdServerError(RequestHeader request, int statusCode, Throwable exception)
```

```scala
override protected def onDevServerError(request: RequestHeader, statusCode: Int, exception: UsefulException): Future[Result]
override protected def onProdServerError(request: RequestHeader, statusCode: Int, exception: UsefulException): Future[Result]
```

If you call `HttpErrorHandler.onServerError` from any of your code you will now need to supply a status code parameter. If you call the `logServerError` method from inside a subclass of `DefaultHttpErrorHandler` you will also need to provide a status code parameter.