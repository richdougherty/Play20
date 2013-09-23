package play.core.system

import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import play.api.PlaySystem
import play.api.{ Logger, Play }
import scala.concurrent.ExecutionContext
import scala.concurrent.forkjoin.{ ForkJoinWorkerThread, ForkJoinPool }
import scala.concurrent.forkjoin.ForkJoinPool.ForkJoinWorkerThreadFactory

object DefaultPlaySystem {

  def apply(
    classloader: ClassLoader,
    config: Config
  ): DefaultPlaySystem = new DefaultPlaySystem(classloader, config)
}

class DefaultPlaySystem(
  classloader: ClassLoader,
  config: Config
) extends PlaySystem {

  // From play.core.Exection

  // FIXME: Make into ClosableLazy
  lazy val internalExecutionContext: scala.concurrent.ExecutionContext = {

    class NamedFjpThread(fjp: ForkJoinPool) extends ForkJoinWorkerThread(fjp)

    /**
     * A named thread factory for the scala fjp as distinct from the Java one.
     */
    case class NamedFjpThreadFactory(name: String) extends ForkJoinWorkerThreadFactory {
      val threadNo = new AtomicInteger()
      val backingThreadFactory = Executors.defaultThreadFactory()

      def newThread(fjp: ForkJoinPool) = {
        val thread = new NamedFjpThread(fjp)
        thread.setName(name + "-" + threadNo.incrementAndGet())
        thread
      }
    }

    val numberOfThreads = play.api.Play.maybeApplication.map(_.configuration.getInt("internal-threadpool-size")).flatten
      .getOrElse(Runtime.getRuntime.availableProcessors)

    ExecutionContext.fromExecutorService(new ForkJoinPool(
      numberOfThreads,
      NamedFjpThreadFactory("play-internal-execution-context"),
      null,
      true))

  }

  // From play.core.Invoker

  private def loadActorConfig(config: Config) = {
    config.getConfig("play")
  }

  // FIXME: Make into ClosableLazy
  lazy val defaultActorSystem: ActorSystem = Play.maybeApplication.map { app =>
    ActorSystem("play", loadActorConfig(app.configuration.underlying), app.classloader)
  } getOrElse {
    Play.logger.warn("No application found at invoker init")
    ActorSystem("play", loadActorConfig(ConfigFactory.load()))
  }

  def defaultExecutionContext: ExecutionContext = defaultActorSystem.dispatcher

}