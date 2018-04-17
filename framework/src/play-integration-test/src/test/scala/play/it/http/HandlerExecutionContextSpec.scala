/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.it.http

import java.util
import java.util.concurrent._

import akka.actor.ActorSystem
import akka.dispatch.ThreadPoolConfig.ThreadPoolExecutorServiceFactory
import akka.dispatch.{DispatcherPrerequisites, ExecutorServiceConfigurator, ExecutorServiceFactory}
import com.typesafe.config.Config
import play.api.mvc._
import play.api.routing.Router
import play.api.test.PlaySpecification
import play.api._
import play.it.test.{ApplicationFactories, ApplicationFactory, EndpointIntegrationSpecification, OkHttpEndpointSupport}

import scala.concurrent.ExecutionContext

class HandlerExecutionContextSpec extends PlaySpecification
  with EndpointIntegrationSpecification with ApplicationFactories with OkHttpEndpointSupport {

  "Play http filters" should {

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

          override def configuration: Configuration = super.configuration + ("akka.actor.default-dispatcher.executor" -> )

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

  /** A default constructor that can be initialized by Akka. */
  def this() = this("unnamed", Vector.empty, ExecutionContext.global)

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

class NamedExecutionContextConfigurator(config: Config, prerequisites: DispatcherPrerequisites) extends ExecutorServiceConfigurator(config, prerequisites) {
  override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = {
      override def createExecutorService: ExecutorService = {
        new ExecutorService {
          override def shutdown(): Unit =
          override def shutdownNow(): util.List[Runnable] = ???
          override def isShutdown: Boolean = ???
          override def isTerminated: Boolean = ???
          override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = ???
          override def submit[T](task: Callable[T]): Future[T] = ???
          override def submit[T](task: Runnable, result: T): Future[T] = ???
          override def submit(task: Runnable): Future[_] = ???
          override def invokeAll[T](tasks: util.Collection[_ <: Callable[T]]): util.List[Future[T]] = ???
          override def invokeAll[T](tasks: util.Collection[_ <: Callable[T]], timeout: Long, unit: TimeUnit): util.List[Future[T]] = ???
          override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]]): T = ???
          override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]], timeout: Long, unit: TimeUnit): T = ???
          override def execute(command: Runnable): Unit = ???
        }
      }
  }
}