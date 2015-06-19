/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server

import java.io._
import java.util.Properties
import play.api._
import play.api.mvc._
import play.api.libs.concurrent.ActorSystemProvider
import play.core._
import play.utils.Threads
import scala.collection.JavaConverters._
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

/**
 * Used to start servers in 'dev' mode, a mode where the application
 * is reloaded whenever its source changes.
 */
object DevServerStart {

  /**
   * Provides an HTTPS-only server for the dev environment.
   *
   * <p>This method uses simple Java types so that it can be used with reflection by code
   * compiled with different versions of Scala.
   */
  def mainDevOnlyHttpsMode(
    buildLink: BuildLink,
    buildDocHandler: BuildDocHandler,
    httpsPort: Int,
    httpAddress: String): ServerWithStop = {
    mainDev(buildLink, buildDocHandler, None, Some(httpsPort), httpAddress)
  }

  /**
   * Provides an HTTP server for the dev environment
   *
   * <p>This method uses simple Java types so that it can be used with reflection by code
   * compiled with different versions of Scala.
   */
  def mainDevHttpMode(
    buildLink: BuildLink,
    buildDocHandler: BuildDocHandler,
    httpPort: Int,
    httpAddress: String): ServerWithStop = {
    mainDev(buildLink, buildDocHandler, Some(httpPort), Option(System.getProperty("https.port")).map(Integer.parseInt(_)), httpAddress)
  }

  private def mainDev(
    buildLink: BuildLink,
    buildDocHandler: BuildDocHandler,
    httpPort: Option[Int],
    httpsPort: Option[Int],
    httpAddress: String): ServerWithStop = {
    val classLoader = getClass.getClassLoader
    Threads.withContextClassLoader(classLoader) {
      try {
        val process = new RealServerProcess(args = Seq.empty)
        val path: File = buildLink.projectPath

        val dirAndDevSettings: Map[String, String] = ServerConfig.rootDirConfig(path) ++ buildLink.settings.asScala.toMap

        // Use plain Java call here in case of scala classloader mess
        {
          if (System.getProperty("play.debug.classpath") == "true") {
            System.out.println("\n---- Current ClassLoader ----\n")
            System.out.println(this.getClass.getClassLoader)
            System.out.println("\n---- The where is Scala? test ----\n")
            System.out.println(this.getClass.getClassLoader.getResource("scala/Predef$.class"))
          }
        }

        // First delete the default log file for a fresh start (only in Dev Mode)
        try {
          new File(path, "logs/application.log").delete()
        } catch {
          case NonFatal(_) =>
        }

        // Configure the logger for the first time.
        // This is usually done by Application itself when it's instantiated, which for other types of ApplicationProviders,
        // is usually instantiated along with or before the provider.  But in dev mode, no application exists initially, so
        // configure it here.
        Logger.init(path, Mode.Dev)

        println(play.utils.Colors.magenta("--- (Running the application, auto-reloading is enabled) ---"))
        println()

        def detectLeaks(leakDetector: Option[LeakDetector]): Unit = {
          for {
            ld <- leakDetector
            leakHandler <- Option(buildLink.reloadLeakHandler)
          } {
            println("Detecting leaks")
            val leakDetected = ld.checkLeak()
            println(s"Leak detected: $leakDetected")
            if (leakDetected) { leakHandler.run() }
          }
        }

        trait ApplicationProviderWithLeakDetector extends ApplicationProvider {
          def currentLeakDetector: Option[LeakDetector]
        }

        // Create reloadable ApplicationProvider
        val appProvider = new ApplicationProviderWithLeakDetector {

          case class State(app: Application, LeakDetector: LeakDetector)

          var lastState: Try[State] = Failure(new PlayException("Not initialized", "?"))
          var currentWebCommands: Option[WebCommands] = None

          override def current: Option[Application] = lastState match {
            case Success(State(app, _)) => Some(app)
            case _ => None
          }

          override def currentLeakDetector: Option[LeakDetector] = lastState match {
            case Success(State(_, ld)) => Some(ld)
            case _ => None
          }

          def get: Try[Application] = {

            synchronized {

              // Let's load the application on another thread
              // as we are now on the Netty IO thread.
              //
              // Because we are on DEV mode here, it doesn't really matter
              // but it's more coherent with the way it works in PROD mode.
              implicit val ec = play.core.Execution.internalContext
              Await.result(scala.concurrent.Future {

                val newLeakDetector = new LeakDetector()

                val reloaded = buildLink.reload match {
                  case NonFatal(t) => Failure(t)
                  case cl: ClassLoader => Success(Some(cl))
                  case null => Success(None)
                }

                reloaded.flatMap { maybeClassLoader =>

                  val maybeNewState: Option[Try[State]] = maybeClassLoader.map { projectClassloader =>
                    try {

                      if (lastState.isSuccess) {
                        println()
                        println(play.utils.Colors.magenta("--- (RELOAD) ---"))
                        println()
                      }

                      val reloadable = this

                      // First, stop the old application if it exists
                      lastState.foreach {
                        case State(app, _) => Play.stop(app)
                      }

                      // Create the new environment
                      val environment = Environment(path, projectClassloader, Mode.Dev)
                      val sourceMapper = new SourceMapper {
                        def sourceOf(className: String, line: Option[Int]) = {
                          Option(buildLink.findSource(className, line.map(_.asInstanceOf[java.lang.Integer]).orNull)).flatMap {
                            case Array(file: java.io.File, null) => Some((file, None))
                            case Array(file: java.io.File, line: java.lang.Integer) => Some((file, Some(line)))
                            case _ => None
                          }
                        }
                      }

                      val webCommands = new DefaultWebCommands
                      currentWebCommands = Some(webCommands)

                      val newApplication = Threads.withContextClassLoader(projectClassloader) {
                        val context = ApplicationLoader.createContext(environment, dirAndDevSettings, Some(sourceMapper), webCommands)
                        val loader = ApplicationLoader(context)
                        loader.load(context)
                      }

                      Play.start(newApplication)

                      newLeakDetector.setTarget(projectClassloader)
                      Success(State(newApplication, newLeakDetector))
                    } catch {
                      case e: PlayException => {
                        lastState = Failure(e)
                        lastState
                      }
                      case NonFatal(e) => {
                        lastState = Failure(UnexpectedException(unexpected = Some(e)))
                        lastState
                      }
                      case e: LinkageError => {
                        lastState = Failure(UnexpectedException(unexpected = Some(e)))
                        lastState
                      }
                    }
                  }

                  val state: Try[State] = maybeNewState match {
                    case Some(newState @ Success(State(newApp, _))) =>
                      val oldLeakDetector: Option[LeakDetector] = currentLeakDetector
                      lastState = newState
                      detectLeaks(oldLeakDetector)
                      newState
                    case _ =>
                      lastState
                  }
                  state.map(_.app)
                }

              }, Duration.Inf)
            }
          }

          override def handleWebCommand(request: play.api.mvc.RequestHeader): Option[Result] = {
            buildDocHandler.maybeHandleDocRequest(request).asInstanceOf[Option[Result]].orElse(
              currentWebCommands.flatMap(_.handleWebCommand(request, buildLink, path))
            )

          }

        }

        // Start server with the application
        val serverConfig = ServerConfig(
          rootDir = path,
          port = httpPort,
          sslPort = httpsPort,
          address = httpAddress,
          mode = Mode.Dev,
          properties = process.properties,
          configuration = Configuration.load(classLoader, System.getProperties, dirAndDevSettings, allowMissingApplicationConf = true)
        )
        val (actorSystem, actorSystemStopHook) = ActorSystemProvider.start(classLoader, serverConfig.configuration)
        val serverStopHook: () => Future[Unit] = () => {
          val actorStop = actorSystemStopHook()
          import play.api.libs.iteratee.Execution.Implicits.trampoline
          actorStop.map { _ =>
            println("Maybe detecting leaks")
            detectLeaks(appProvider.currentLeakDetector)
          }
        }
        val serverContext = ServerProvider.Context(serverConfig, appProvider, actorSystem, serverStopHook)
        val serverProvider = ServerProvider.fromConfiguration(classLoader, serverConfig.configuration)
        serverProvider.createServer(serverContext)
      } catch {
        case e: ExceptionInInitializerError => throw e.getCause
      }

    }
  }

}