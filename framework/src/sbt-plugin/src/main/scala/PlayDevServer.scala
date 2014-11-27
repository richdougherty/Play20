package play

import java.io.{ Closeable, File }
import java.net.{ InetSocketAddress, URL, URLClassLoader }
import java.util.jar.JarFile
import play.core.buildlink.application._
import play.core.classloader._
import play.runsupport.AssetsClassLoader


object PlayDevServer {

  case class Config(
    commonClasspath: Seq[File],
    dependencyClasspath: Seq[File],
    docsClasspath: Seq[File],
    allAssets: Seq[(String, File)],
    httpPort: Option[Int],
    httpsPort: Option[Int],
    systemProperties: Seq[(String, String)],
    projectPath: File,
    settings: java.util.Map[String, String]
  ) {
    assert(httpPort.isDefined || httpsPort.isDefined)
  }

  sealed trait BuildResult
  case object SameBuild extends BuildResult
  case class NewBuild(classpath: Seq[File]) extends BuildResult
  case class FailedBuild(t: Throwable) extends BuildResult

  trait SbtLink extends Closeable {
    def build(): BuildResult
    def findSource(className: String, line: java.lang.Integer): Array[java.lang.Object]
    def runTask(task: String): AnyRef
    def beforeRunStarted(): Unit
    def afterRunStarted(address: InetSocketAddress): Unit
    def afterRunStopped(): Unit
    def onRunError(): Unit
  }

  def start(
    commonClassLoaderProvider: PlayCommonClassLoaderProvider,
    config: Config,
    sbtLink: SbtLink /* TODO: rename */): Closeable = {

    /*
     * We need to do a bit of classloader magic to run the Play application.
     *
     * There are seven classloaders:
     *
     * 1. buildLoader, the classloader of sbt and the Play sbt plugin.
     * 2. commonLoader, a classloader that persists across calls to run.
     *    This classloader is stored inside the
     *    PlayInternalKeys.playCommonClassloader task. This classloader will
     *    load the classes for the H2 database if it finds them in the user's
     *    classpath. This allows H2's in-memory database state to survive across
     *    calls to run.
     * 3. delegatingLoader, a special classloader that overrides class loading
     *    to delegate shared classes for build link to the buildLoader, and accesses
     *    the reloader.currentApplicationClassLoader for resource loading to
     *    make user resources available to dependency classes.
     *    Has the commonLoader as its parent.
     * 4. applicationLoader, contains the application dependencies. Has the
     *    delegatingLoader as its parent. Classes from the commonLoader and
     *    the delegatingLoader are checked for loading first.
     * 5. docsLoader, the classloader for the special play-docs application
     *    that is used to serve documentation when running in development mode.
     *    Has the applicationLoader as its parent for Play dependencies and
     *    delegation to the shared sbt doc link classes.
     * 6. playAssetsClassLoader, serves assets from all projects, prefixed as
     *    configured.  It does no caching, and doesn't need to be reloaded each
     *    time the assets are rebuilt.
     * 7. reloader.currentApplicationClassLoader, contains the user classes
     *    and resources. Has applicationLoader as its parent, where the
     *    application dependencies are found, and which will delegate through
     *    to the buildLoader via the delegatingLoader for the shared link.
     *    Resources are actually loaded by the delegatingLoader, where they
     *    are available to both the reloader and the applicationLoader.
     *    This classloader is recreated on reload. See PlayReloader.
     *
     * Someone working on this code in the future might want to tidy things up
     * by splitting some of the custom logic out of the URLClassLoaders and into
     * their own simpler ClassLoader implementations. The curious cycle between
     * applicationLoader and reloader.currentApplicationClassLoader could also
     * use some attention.
     */

    val buildLoader = this.getClass.getClassLoader

    def urls(files: Seq[File]): Array[URL] = files.map(_.toURI.toURL).toArray

    val commonLoader = commonClassLoaderProvider.getOrElseUseClasspath(config.commonClasspath)
    val commonAndBuildLoader = new CombiningBuildClassLoader(commonLoader, buildLoader)

    /**
     * ClassLoader that delegates loading of shared build link classes to the
     * buildLoader. Also accesses the reloader resources to make these available
     * to the applicationLoader, creating a full circle for resource loading.
     */
    lazy val delegatingLoader: ClassLoader = new DelegatingClassLoader(
      commonAndBuildLoader,
      new ApplicationClassLoaderProvider {
        def get: ClassLoader = { applicationLink.getClassLoader.orNull }
      }
    )

    lazy val applicationLoader = new URLClassLoader(urls(config.dependencyClasspath), delegatingLoader) {
      override def toString = "PlayDependencyClassLoader{" + getURLs.map(_.toString).mkString(", ") + "}"
    }
    lazy val assetsLoader = new AssetsClassLoader(applicationLoader, config.allAssets)

    lazy val applicationLink: PlayApplicationLink = new PlayApplicationLink(sbtLink, assetsLoader)

    // Set Java properties
    config.systemProperties.foreach {
      case (key, value) => System.setProperty(key, value)
    }

    try {
      // Now we're about to start, let's call the hooks:
      sbtLink.beforeRunStarted()

      // Get a handler for the documentation. The documentation content lives in play/docs/content
      // within the play-docs JAR.
      val docsLoader = new URLClassLoader(urls(config.docsClasspath), applicationLoader)
      val docsJarFile = {
        val f = config.docsClasspath.filter(_.getName.startsWith("play-docs")).head
        new JarFile(f)
      }
      val server = {
        val mainClass = applicationLoader.loadClass("play.core.server.NettyServer")
        val devModeConfig = new DevModeConfig {
          override val applicationBuildLink = new ApplicationBuildLink {
            override def reload(): java.lang.Object = applicationLink.reload()
            override def forceReload(): Unit = applicationLink.forceReload()
            override def findSource(className: String, line: java.lang.Integer): Array[java.lang.Object] =
              applicationLink.findSource(className, line)
          }
          override val buildDocHandler: BuildDocHandler = {
            val docHandlerFactoryClass = docsLoader.loadClass("play.docs.BuildDocHandlerFactory")
            val factoryMethod = docHandlerFactoryClass.getMethod("fromJar", classOf[JarFile], classOf[String])
            factoryMethod.invoke(null, docsJarFile, "play/docs/content").asInstanceOf[BuildDocHandler]
          }
          override val projectPath: File = config.projectPath
          override val settings: java.util.Map[String, String] = config.settings
        }
        if (config.httpPort.isDefined) {
          val mainDev = mainClass.getMethod("mainDevHttpMode", classOf[DevModeConfig], classOf[Int])
          mainDev.invoke(null, devModeConfig, config.httpPort.get: java.lang.Integer).asInstanceOf[DevModeServer]
        } else {
          val mainDev = mainClass.getMethod("mainDevOnlyHttpsMode", classOf[DevModeConfig], classOf[Int])
          mainDev.invoke(null, devModeConfig, config.httpsPort.get: java.lang.Integer).asInstanceOf[DevModeServer]
        }
      }

      // Notify hooks
      sbtLink.afterRunStarted(server.mainAddress)

      new Closeable {
        def close() = {
          server.stop()
          docsJarFile.close()
          sbtLink.close()
          applicationLink.close()

          // Notify hooks
          sbtLink.afterRunStopped()

          // Remove Java properties
          config.systemProperties.foreach {
            case (key, _) => System.clearProperty(key)
          }
        }
      }
    } catch {
      case e: Throwable =>
        sbtLink.onRunError()
        throw e
    }
  }

  private class PlayApplicationLink(sbtLink: SbtLink, baseLoader: ClassLoader) extends Closeable {
    // Flag to force a reload on the next request.
    // This is set if a compile error occurs, and also by the forceReload method on BuildLink, which is called for
    // example when evolutions have been applied.
    @volatile private var forceReloadNextTime = false
    // The current classpath for the application. This value starts out empty
    // but changes on each successful new compilation.
    @volatile private var currentApplicationClasspath: Option[Seq[File]] = None
    // The current classloader for the application. This value starts out empty
    // but changes on each successful new compilation.
    @volatile private var currentApplicationClassLoader: Option[ClassLoader] = None

    private val classLoaderVersion = new java.util.concurrent.atomic.AtomicInteger(0)

    def reload(): AnyRef = {
      play.Play.synchronized {

        def classLoaderFromClasspath(classpath: Seq[File]): ClassLoader = {
          // Create a new classloader
          val version = classLoaderVersion.incrementAndGet
          val name = "ReloadableClassLoader(v" + version + ")"
          val urls = classpath.map(_.toURI.toURL)
          val loader = new DelegatedResourcesClassLoader(name, urls.toArray, baseLoader)
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

  private class DelegatedResourcesClassLoader(name: String, urls: Array[URL], parent: ClassLoader) extends java.net.URLClassLoader(urls, parent) {
    require(parent ne null)
    override def getResources(name: String): java.util.Enumeration[java.net.URL] = getParent.getResources(name)
    override def toString = name + "{" + getURLs.map(_.toString).mkString(", ") + "}"
  }

}