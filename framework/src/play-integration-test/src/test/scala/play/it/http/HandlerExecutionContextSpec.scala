/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.it.http

import play.api.mvc._
import play.api.routing.Router
import play.api.test.PlaySpecification
import play.api.{Application, ApplicationLoader, BuiltInComponentsFromContext, Environment}
import play.it.test.{ApplicationFactories, ApplicationFactory, EndpointIntegrationSpecification, OkHttpEndpointSupport}

import scala.concurrent.ExecutionContext

class HandlerExecutionContextSpec extends PlaySpecification
  with EndpointIntegrationSpecification with ApplicationFactories with OkHttpEndpointSupport {

  "Play http filters" should {

    object ThreadLog {
      private val threadLocal = new ThreadLocal[Vector[String]]() {
        override def initialValue(): Vector[String] = Vector.empty
      }
      def get() = threadLocal.get()
      def log(msg: String) = threadLocal.set(threadLocal.get() :+ msg)
      def runWith[A](log: Vector[String])(block: => A): A = {
        val oldLog = get()
        threadLocal.set(log)
        try block finally threadLocal.set(oldLog)
      }
    }

    class NamedExecutionContext(name: String, preparedLog: Vector[String], delegate: ExecutionContext) extends ExecutionContext {

      override def execute(runnable: Runnable): Unit = {
        delegate.execute(new Runnable {
          override def run(): Unit = ThreadLog.runWith(preparedLog) {
            ThreadLog.log(s"ec-$name-run")
            runnable.run()
          }
        })
      }

      override def prepare(): ExecutionContext = {
        val currLog: Vector[String] = ThreadLog.get()
        val prepareLog = currLog :+ s"ec-$name-prepare"
        new NamedExecutionContext(name, prepareLog, delegate.prepare())
      }
      override def reportFailure(cause: Throwable): Unit = delegate.reportFailure(cause)
    }

    val appFactory: ApplicationFactory = new ApplicationFactory {
      override def create(): Application = {
        val components = new BuiltInComponentsFromContext(
          ApplicationLoader.Context.create(Environment.simple())) {
          application =>
          import play.api.mvc.Results._
          import play.api.routing.sird
          import play.api.routing.sird._

          private lazy val ec1 = new NamedExecutionContext("1", Vector.empty, actorSystem.dispatcher)
          //private val ec2 = new NamedExecutionContext("2", Vector.empty, actorSystem.dispatcher)

          override lazy val executionContext = ec1

          override lazy val httpFilters = Seq[EssentialFilter](
            new EssentialFilter {
              override def apply(next: EssentialAction): EssentialAction = {
                ThreadLog.runWith[EssentialAction](Vector.empty) {
                  ThreadLog.log("filter-apply")
                  next()
                }
              }
            }
          )

          override lazy val router: Router = Router.from {
            case sird.GET(p"/") => Action {
              ThreadLog.log("action-body")
              Ok(ThreadLog.get().mkString(","))
            }
          }

//          override lazy val httpErrorHandler: HttpErrorHandler = new HttpErrorHandler {
//            override def onServerError(request: RequestHeader, exception: Throwable) = {
//              Future(InternalServerError(exception.getMessage))
//            }
//            override def onClientError(request: RequestHeader, statusCode: Int, message: String) = {
//              Future(InternalServerError(message))
//            }
//          }
        }
        components.application
      }
    }

    "send exceptions from Filters to the HttpErrorHandler" in appFactory.withAllOkHttpEndpoints { endpoint =>
      val response = endpoint.call("/")
      response.code must_== 200
      response.body.string must_== "ec-1-prepare,ec-1-run,filter-apply,action-body"
    }

  }
}
