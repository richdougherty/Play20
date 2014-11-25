/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play

import play.runsupport.PlayWatchService
import play.sbtplugin.run._

import play.api._
import play.core._
// import play.core.buildlink._
// import play.core.buildlink.application._
import play.core.buildlink.sbt.{ BuildResult, FailedBuild, FreshBuild, SameBuild }
import sbt._
import sbt.Keys._
import scala.concurrent.{ Future, Promise }
import play.PlayImport._
import PlayKeys._
import PlayExceptions._

trait PlayReloader {
  this: PlayCommands with PlayPositionMapper =>

  sealed trait BuildResult
  case object SameBuild extends BuildResult
  case class NewBuild(classpath: Classpath) extends BuildResult
  case class FailedBuild(t: Throwable) extends BuildResult

  trait PlaySbtLink {
    def build(): BuildResult
    def findSource(className: String, line: java.lang.Integer): Array[java.lang.Object]
    def close(): Unit
  }

  trait PlayApplicationLink {
    def reload(): java.lang.Object
    def forceReload(): Unit
    def getClassLoader: Option[ClassLoader]
    def findSource(className: String, line: java.lang.Integer): Array[java.lang.Object]
    def close(): Unit
  }

  /**
   * An extension of BuildLink to provide internal methods to PlayRun, such as the ability to get the classloader,
   * and the ability to close it (stop watching files).
   */
  trait PlayBuildLink {
    def sbtLink: PlaySbtLink
    def applicationLink: PlayApplicationLink
    def projectPath(): File
    def settings(): java.util.Map[String, String]
    def close(): Unit
  }

  /**
   * Create a new reloader
   */
  def newReloader(state: State,
    playReload: TaskKey[sbt.inc.Analysis],
    createClassLoader: ClassLoaderCreator,
    classpathTask: TaskKey[Classpath],
    baseLoader: ClassLoader,
    monitoredFiles: Seq[String],
    playWatchService: PlayWatchService): PlayBuildLink = {

    val extracted = Project.extract(state)

    new PlayBuildLink {

      lazy val projectPath = extracted.currentProject.base

      val sbtLink = new PlaySbtLink {
        // Whether any source files have changed since the last request.
        @volatile private var changed = false
        // The last successful compile results. Used for rendering nice errors.
        @volatile private var currentAnalysis = Option.empty[sbt.inc.Analysis]
        @volatile private var isBuilt = false
        // A watch state for the classpath. Used to determine whether anything on the classpath has changed as a result
        // of compilation, and therefore a new classloader is needed and the app needs to be reloaded.
        @volatile private var watchState: WatchState = WatchState.empty

        // Create the watcher, updates the changed boolean when a file has changed.
        private val watcher = playWatchService.watch(monitoredFiles.map(new File(_)), () => {
          changed = true
        })

        def build(): BuildResult = if (changed || currentAnalysis.isEmpty || !isBuilt) {
          changed = false // DATA RACE

          def runTask[T](taskKey: Def.ScopedKey[Task[T]]): Either[FailedBuild, T] = {
            val result: Result[T] = Project.runTask(taskKey, state).get._2
            result.toEither.left.map { incomplete: Incomplete =>
              // We force reload next time because compilation failed this time
              isBuilt = false
              val t: Throwable = Incomplete.allExceptions(incomplete).headOption.map {
                case e: PlayException => e
                case e: xsbti.CompileFailed =>
                  getProblems(incomplete)
                    .find(_.severity == xsbti.Severity.Error)
                    .map(CompilationException)
                    .getOrElse(UnexpectedException(Some("The compilation failed without reporting any problem!"), Some(e)))
                case e: Exception => UnexpectedException(unexpected = Some(e))
              }.getOrElse {
                UnexpectedException(Some("The compilation task failed without any exception!"))
              }
              FailedBuild(t)
            }
          }

          def runPlayReloadTask(): Either[FailedBuild, Unit] = {
            runTask(playReload).right.map { compilationResult =>
              currentAnalysis = Some(compilationResult)
              ()
            }
          }

          def runPlayClasspathTask(): Either[FailedBuild, Classpath] = {
            runTask(classpathTask)
          }

          def classpathChanged(classpath: Classpath): Boolean = {
              // We only want to reload if the classpath has changed.  Assets don't live on the classpath, so
              // they won't trigger a reload.
              // Use the SBT watch service, passing true as the termination to force it to break after one check
              val (_, newState) = SourceModificationWatch.watch(classpath.files.***, 0, watchState)(true)
              watchState = newState
              // SBT has a quiet wait period, if that's set to true, sources were modified
              newState.awaitingQuietPeriod
          }

          // Run the reload task, which will trigger everything to compile
          runPlayReloadTask().right.flatMap { _ =>
            // Calculate the classpath
            runPlayClasspathTask().right.map { classpath =>
              val triggered = classpathChanged(classpath)
              if (triggered || !isBuilt) {
                isBuilt = true
                NewBuild(classpath)
              } else {
                SameBuild
              }
            }
          }.fold(
            identity[FailedBuild],
            identity[BuildResult]
          )
        } else {
          SameBuild
        }

        private def getProblems(incomplete: Incomplete): Seq[xsbti.Problem] = {
          def remapProblemForGeneratedSources(problem: xsbti.Problem) = {
            val mappedPosition = playPositionMapper(problem.position)
            mappedPosition.map { pos =>
              new xsbti.Problem {
                def message = problem.message
                def category = ""
                def position = pos
                def severity = problem.severity
              }
            } getOrElse problem
          }

          val exceptionProblems: Seq[xsbti.Problem] = Incomplete.allExceptions(incomplete :: Nil).toSeq flatMap {
            case cf: xsbti.CompileFailed => cf.problems
            case _ => Seq.empty
          }

          val javacProblems: Seq[xsbti.Problem] = {
            Incomplete.linearize(incomplete).filter(i => i.node.isDefined && i.node.get.isInstanceOf[ScopedKey[_]]).flatMap { i =>
              val JavacError = """\[error\]\s*(.*[.]java):(\d+):\s*(.*)""".r
              val JavacErrorInfo = """\[error\]\s*([a-z ]+):(.*)""".r
              val JavacErrorPosition = """\[error\](\s*)\^\s*""".r

              Project.runTask(streamsManager, state).map(_._2).get.toEither.right.toOption.map { streamsManager =>
                var first: (Option[(String, String, String)], Option[Int]) = (None, None)
                var parsed: (Option[(String, String, String)], Option[Int]) = (None, None)
                Output.lastLines(i.node.get.asInstanceOf[ScopedKey[_]], streamsManager, None).map(_.replace(scala.Console.RESET, "")).map(_.replace(scala.Console.RED, "")).collect {
                  case JavacError(file, line, message) => parsed = Some((file, line, message)) -> None
                  case JavacErrorInfo(key, message) => parsed._1.foreach { o =>
                    parsed = Some((parsed._1.get._1, parsed._1.get._2, parsed._1.get._3 + " [" + key.trim + ": " + message.trim + "]")) -> None
                  }
                  case JavacErrorPosition(pos) =>
                    parsed = parsed._1 -> Some(pos.size)
                    if (first == ((None, None))) {
                      first = parsed
                    }
                }
                first
              }.collect {
                case (Some(error), maybePosition) => new xsbti.Problem {
                  def message = error._3
                  def category = ""
                  def position = new xsbti.Position {
                    def line = xsbti.Maybe.just(error._2.toInt)
                    def lineContent = ""
                    def offset = xsbti.Maybe.nothing[java.lang.Integer]
                    def pointer = maybePosition.map(pos => xsbti.Maybe.just((pos - 1).asInstanceOf[java.lang.Integer])).getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                    def pointerSpace = xsbti.Maybe.nothing[String]
                    def sourceFile = xsbti.Maybe.just(file(error._1))
                    def sourcePath = xsbti.Maybe.just(error._1)
                  }
                  def severity = xsbti.Severity.Error
                }
              }

            }
          }

          (exceptionProblems ++ javacProblems).map(remapProblemForGeneratedSources)
        }

        def findSource(className: String, line: java.lang.Integer): Array[java.lang.Object] = {
          val topType = className.split('$').head
          currentAnalysis.flatMap { analysis =>
            analysis.apis.internal.flatMap {
              case (sourceFile, source) =>
                source.api.definitions.find(defined => defined.name == topType).map(_ => {
                  sourceFile: java.io.File
                } -> line)
            }.headOption.map {
              case (source, maybeLine) =>
                play.twirl.compiler.MaybeGeneratedSource.unapply(source).map { generatedSource =>
                  generatedSource.source.get -> Option(maybeLine).map(l => generatedSource.mapLine(l): java.lang.Integer).orNull
                }.getOrElse(source -> maybeLine)
            }
          }.map {
            case (file, l) =>
              Array[java.lang.Object](file, l)
          }.orNull
        }

        def close() = {
          currentAnalysis = None
          watcher.stop()
        }
      }

      val applicationLink = new PlayApplicationLink {
        // Flag to force a reload on the next request.
        // This is set if a compile error occurs, and also by the forceReload method on BuildLink, which is called for
        // example when evolutions have been applied.
        @volatile private var forceReloadNextTime = false
        // The current classpath for the application. This value starts out empty
        // but changes on each successful new compilation.
        @volatile private var currentApplicationClasspath: Option[Classpath] = None
        // The current classloader for the application. This value starts out empty
        // but changes on each successful new compilation.
        @volatile private var currentApplicationClassLoader: Option[ClassLoader] = None

        private val classLoaderVersion = new java.util.concurrent.atomic.AtomicInteger(0)

        def reload: AnyRef = {
          play.Play.synchronized {

            def classLoaderFromClasspath(classpath: Classpath): ClassLoader = {
              // Create a new classloader
              val version = classLoaderVersion.incrementAndGet
              val name = "ReloadableClassLoader(v" + version + ")"
              val urls = Path.toURLs(classpath.files)
              val loader = createClassLoader(name, urls, baseLoader)
              currentApplicationClassLoader = Some(loader)
              loader
            }

            def cacheBuildResultClasspath(buildResult: BuildResult): Unit = {
              // cache classpath in case we need to force a reload
              buildResult match {
                case _: FailedBuild =>
                  currentApplicationClasspath = None
                case NewBuild(classpath) =>
                  currentApplicationClasspath = Some(classpath)
                case _ =>
              }
            }

            def reloadWithBuildResult(buildResult: BuildResult): AnyRef = {
              if (forceReloadNextTime) {
                forceReloadNextTime = false // DATA RACE
                buildResult match {
                  case FailedBuild(t) =>
                    t
                  case _ =>
                    assert(currentApplicationClasspath.isDefined, "If not FailedBuild, then at least one build must have succeeded.")
                    classLoaderFromClasspath(currentApplicationClasspath.get)
                }
              } else {
                buildResult match {
                  case FailedBuild(t) =>
                    t
                  case NewBuild(classpath) =>
                    classLoaderFromClasspath(classpath)
                  case SameBuild =>
                    null
                }
              }
            }


            val buildResult: BuildResult = sbtLink.build()
            cacheBuildResultClasspath(buildResult)
            reloadWithBuildResult(buildResult)
          }
        }
        def forceReload() {
          forceReloadNextTime = true
        }
        def getClassLoader = currentApplicationClassLoader

        def findSource(className: String, line: java.lang.Integer): Array[java.lang.Object] = {
          sbtLink.findSource(className, line)
        }

        def close() = {
          currentApplicationClassLoader = None
        }
      }


      lazy val settings = {
        import scala.collection.JavaConverters._
        extracted.get(devSettings).toMap.asJava
      }

      def runTask(task: String): AnyRef = {
        val parser = Act.scopedKeyParser(state)
        val Right(sk) = complete.DefaultParsers.result(parser, task)
        val result = Project.runTask(sk.asInstanceOf[Def.ScopedKey[Task[AnyRef]]], state).map(_._2)

        result.flatMap(_.toEither.right.toOption).orNull
      }

      def close() = {
        sbtLink.close()
        applicationLink.close()
      }
    }

  }
}
