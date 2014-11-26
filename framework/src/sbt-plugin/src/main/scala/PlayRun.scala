/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play

import java.io.Closeable

import com.typesafe.sbt.web.SbtWeb
import sbt._
import Keys._
import play.PlayImport._
import PlayKeys._
import play.sbtplugin.Colors
import play.core.buildlink.application._
import annotation.tailrec
import scala.collection.JavaConverters._
import java.net.URLClassLoader
import com.typesafe.sbt.SbtNativePackager._
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.web.SbtWeb.autoImport._
import play.runsupport.PlayWatchService
import play.sbtplugin.run._

/**
 * Provides mechanisms for running a Play application in SBT
 */
trait PlayRun extends PlayInternalKeys {
  this: PlayReloader =>

  /**
   * Configuration for the Play docs application's dependencies. Used to build a classloader for
   * that application. Hidden so that it isn't exposed when the user application is published.
   */
  val DocsApplication = config("docs").hide

  // Regex to match Java System Properties of the format -Dfoo=bar
  private val SystemProperty = "-D([^=]+)=(.*)".r

  /**
   * Take all the options in javaOptions of the format "-Dfoo=bar" and return them as a Seq of key value pairs of the format ("foo" -> "bar")
   */
  private def extractSystemProperties(javaOptions: Seq[String]): Seq[(String, String)] = {
    javaOptions.collect { case SystemProperty(key, value) => key -> value }
  }

  private def parsePort(portString: String): Int = {
    try {
      Integer.parseInt(portString)
    } catch {
      case e: NumberFormatException => sys.error("Invalid port argument: " + portString)
    }
  }

  private def filterArgs(args: Seq[String], defaultHttpPort: Int): (Seq[(String, String)], Option[Int], Option[Int]) = {
    val (properties, others) = args.span(_.startsWith("-D"))

    val javaProperties: Seq[(String, String)] = properties.map(_.drop(2).split('=')).map(a => a(0) -> a(1)).toSeq

    // collect arguments plus config file property if present
    val httpPort = Option(System.getProperty("http.port"))
    val httpsPort = Option(System.getProperty("https.port"))

    //port can be defined as a numeric argument or as disabled, -Dhttp.port argument or a generic sys property
    val maybePort = others.headOption.orElse(javaProperties.toMap.get("http.port")).orElse(httpPort)
    val maybeHttpsPort = javaProperties.toMap.get("https.port").orElse(httpsPort).map(parsePort)
    if (maybePort.exists(_ == "disabled")) (javaProperties, Option.empty[Int], maybeHttpsPort)
    else (javaProperties, maybePort.map(parsePort).orElse(Some(defaultHttpPort)), maybeHttpsPort)
  }

  val playDefaultRunTask = playRunTask(playRunHooks, playDependencyClasspath,
    playReloaderClasspath, playAllAssets)

  /**
   * This method is public API, used by sbt-echo, which is used by Activator:
   *
   * https://github.com/typesafehub/sbt-echo/blob/v0.1.3/play/src/main/scala-sbt-0.13/com/typesafe/sbt/echo/EchoPlaySpecific.scala#L20
   *
   * Do not change its signature without first consulting the Activator team.  Do not change its signature in a minor
   * release.
   */
  def playRunTask(runHooks: TaskKey[Seq[play.PlayRunHook]],
    dependencyClasspath: TaskKey[Classpath],
    reloaderClasspath: TaskKey[Classpath],
    allAssets: TaskKey[Seq[(String, File)]]): Def.Initialize[InputTask[Unit]] = Def.inputTask {

    val args = Def.spaceDelimited().parsed

    val state = Keys.state.value
    val interaction = playInteractionMode.value

    lazy val devModeServer = startDevMode(
      state,
      runHooks.value,
      (javaOptions in Runtime).value,
      dependencyClasspath.value,
      reloaderClasspath,
      allAssets.value,
      playCommonClasspath.value,
      playMonitoredFiles.value,
      playWatchService.value,
      (managedClasspath in DocsApplication).value,
      interaction,
      playDefaultPort.value,
      args
    )

    interaction match {
      case nonBlocking: PlayNonBlockingInteractionMode =>
        nonBlocking.start(devModeServer)
      case blocking =>
        devModeServer

        println()
        println(Colors.green("(Server started, use Ctrl+D to stop and go back to the console...)"))
        println()

        // If we have both Watched.Configuration and Watched.ContinuousState
        // attributes and if Watched.ContinuousState.count is 1 then we assume
        // we're in ~ run mode
        val maybeContinuous = for {
          watched <- state.get(Watched.Configuration)
          watchState <- state.get(Watched.ContinuousState)
          if watchState.count == 1
        } yield watched

        maybeContinuous match {
          case Some(watched) =>
            // ~ run mode
            interaction doWithoutEcho {
              twiddleRunMonitor(watched, state, Some(WatchState.empty))
            }
          case None =>
            // run mode
            interaction.waitForCancel()
        }

        devModeServer.close()
        println()
    }
  }

  /**
   * Monitor changes in ~run mode.
   */
  @tailrec
  private def twiddleRunMonitor(watched: Watched, state: State, ws: Option[WatchState] = None): Unit = {
    val ContinuousState = AttributeKey[WatchState]("watch state", "Internal: tracks state for continuous execution.")
    def isEOF(c: Int): Boolean = c == 4

    @tailrec def shouldTerminate: Boolean = (System.in.available > 0) && (isEOF(System.in.read()) || shouldTerminate)

    val sourcesFinder = PathFinder { watched watchPaths state }
    val watchState = ws.getOrElse(state get ContinuousState getOrElse WatchState.empty)

    val (triggered, newWatchState, newState) =
      try {
        val (triggered, newWatchState) = SourceModificationWatch.watch(sourcesFinder, watched.pollInterval, watchState)(shouldTerminate)
        (triggered, newWatchState, state)
      } catch {
        case e: Exception =>
          val log = state.log
          log.error("Error occurred obtaining files to watch.  Terminating continuous execution...")
          (false, watchState, state.fail)
      }

    if (triggered) {
      //Then launch compile
      Project.synchronized {
        val start = System.currentTimeMillis
        Project.runTask(compile in Compile, newState).get._2.toEither.right.map { _ =>
          val duration = System.currentTimeMillis - start
          val formatted = duration match {
            case ms if ms < 1000 => ms + "ms"
            case seconds => (seconds / 1000) + "s"
          }
          println("[" + Colors.green("success") + "] Compiled in " + formatted)
        }
      }

      // Avoid launching too much compilation
      Thread.sleep(Watched.PollDelayMillis)

      // Call back myself
      twiddleRunMonitor(watched, newState, Some(newWatchState))
    } else {
      ()
    }
  }

  /**
   * Start the Play server in dev mode
   *
   * @return A closeable that can be closed to stop the server
   */
  private def startDevMode(state: State, runHooks: Seq[play.PlayRunHook], javaOptions: Seq[String],
    dependencyClasspath: Classpath,
    reloaderClasspathTask: TaskKey[Classpath],
    allAssets: Seq[(String, File)], commonClasspath: Classpath,
    monitoredFiles: Seq[String], playWatchService: PlayWatchService,
    docsClasspath: Classpath, interaction: PlayInteractionMode, defaultHttpPort: Int,
    args: Seq[String]): Closeable = {

    val (properties, httpPort, httpsPort) = filterArgs(args, defaultHttpPort = defaultHttpPort)
    val systemProperties = extractSystemProperties(javaOptions)

    require(httpPort.isDefined || httpsPort.isDefined, "You have to specify https.port when http.port is disabled")

    println()


    val sbtLink: PlayDevServer.SbtLink = newReloader(state, runHooks, playReload, reloaderClasspathTask,
      monitoredFiles, playWatchService)

    // Get the Files from a Classpath
    def files(cp: Classpath): Seq[File] = cp.map(_.data)//.toURI.toURL).toArray

    val extracted = Project.extract(state)

    val config = PlayDevServer.Config(
      commonClasspath = files(commonClasspath),
      dependencyClasspath = files(dependencyClasspath),
      docsClasspath = files(docsClasspath),
      allAssets = allAssets,
      systemProperties = properties ++ systemProperties,
      httpPort = httpPort,
      httpsPort = httpsPort,
      projectPath = extracted.currentProject.base,
      settings = {
        import scala.collection.JavaConverters._
        extracted.get(devSettings).toMap.asJava
      }
    )

    PlayDevServer.start(commonClassLoaderProvider, config, sbtLink)
  }

  val playPrefixAndAssetsSetting = playPrefixAndAssets := {
    assetsPrefix.value -> (WebKeys.public in Assets).value
  }

  val playAllAssetsSetting = playAllAssets := {
    if (playAggregateAssets.value) {
      (playPrefixAndAssets ?).all(ScopeFilter(
        inDependencies(ThisProject),
        inConfigurations(Compile)
      )).value.flatten
    } else {
      Seq(playPrefixAndAssets.value)
    }
  }

  // val playAssetsClassLoaderSetting = playAssetsClassLoader := { parent =>
  //   new AssetsClassLoader(parent, playAllAssets.value)
  // }

  val playPrefixAndPipelineSetting = playPrefixAndPipeline := {
    assetsPrefix.value -> (WebKeys.pipeline in Assets).value
  }

  val playPackageAssetsMappingsSetting = playPackageAssetsMappings := {
    val allPipelines =
      if (playAggregateAssets.value) {
        (playPrefixAndPipeline ?).all(ScopeFilter(
          inDependencies(ThisProject),
          inConfigurations(Compile)
        )).value.flatten
      } else {
        Seq(playPrefixAndPipeline.value)
      }

    val allMappings = allPipelines.flatMap {
      case (prefix, pipeline) => pipeline.map {
        case (file, path) => file -> (prefix + path)
      }
    }
    SbtWeb.deduplicateMappings(allMappings, Seq(_.headOption))
  }

  val playStartCommand = Command.args("start", "<port>") { (state: State, args: Seq[String]) =>

    val extracted = Project.extract(state)

    val interaction = extracted.get(playInteractionMode)
    // Parse HTTP port argument
    val (properties, httpPort, httpsPort) = filterArgs(args, defaultHttpPort = extracted.get(playDefaultPort))
    require(httpPort.isDefined || httpsPort.isDefined, "You have to specify https.port when http.port is disabled")

    Project.runTask(stage, state).get._2.toEither match {
      case Left(_) =>
        println()
        println("Cannot start with errors.")
        println()
        state.fail
      case Right(_) =>
        val stagingBin = Some(extracted.get(stagingDirectory in Universal) / "bin" / extracted.get(normalizedName in Universal)).map {
          f =>
            if (System.getProperty("os.name").toLowerCase.contains("win")) f.getAbsolutePath + ".bat" else f.getAbsolutePath
        }.get
        val javaProductionOptions = Project.runTask(javaOptions in Production, state).get._2.toEither.right.getOrElse(Seq[String]())

        // Note that I'm unable to pass system properties along with properties... if I do then I receive:
        //  java.nio.charset.IllegalCharsetNameException: "UTF-8"
        // Things are working without passing system properties, and I'm unsure that they need to be passed explicitly. If def main(args: Array[String]){
        // problem occurs in this area then at least we know what to look at.
        val args = Seq(stagingBin) ++
          properties.map {
            case (key, value) => s"-D$key=$value"
          } ++
          javaProductionOptions ++
          Seq("-Dhttp.port=" + httpPort.getOrElse("disabled"))
        val builder = new java.lang.ProcessBuilder(args.asJava)
        new Thread {
          override def run() {
            System.exit(Process(builder) !)
          }
        }.start()

        println(Colors.green(
          """|
            |(Starting server. Type Ctrl+D to exit logs, the server will remain in background)
            | """.stripMargin))

        interaction.waitForCancel()

        println()

        state.copy(remainingCommands = Seq.empty)
    }

  }

}
