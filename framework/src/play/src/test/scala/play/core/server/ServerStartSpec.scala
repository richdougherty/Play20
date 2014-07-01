/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server

import com.google.common.io.Files
import java.io.File
import java.nio.charset.Charset
import java.util.Properties
import org.specs2.mutable.Specification
import play.api.Play
import play.core.ApplicationProvider
import play.core.server.netty.{ NettyServer => NewNettyServer }

object ServerStartSpec extends Specification {

  sequential

  def withTempDir[T](block: File => T) = {
    val temp = Files.createTempDir()
    try {
      block(temp)
    } finally {
      def rm(file: File): Unit = file match {
        case dir if dir.isDirectory =>
          dir.listFiles().foreach(rm)
          dir.delete()
        case f => f.delete()
      }
      rm(temp)
    }
  }

  case class ExitException(message: String, cause: Option[Throwable] = None, returnCode: Int = -1) extends Exception(s"Exit with $message, $cause, $returnCode")

  class FakeServerProcess(
    val args: Seq[String] = Seq(),
    propertyMap: Map[String,String] = Map(),
    val pid: Option[String] = None
  ) extends ServerProcess {

    val classLoader: ClassLoader = getClass.getClassLoader

    val properties = new Properties()
    for ((k, v) <- propertyMap) { properties.put(k, v) }

    private var hooks = Seq.empty[() => Unit]
    def addShutdownHook(hook: => Unit) = {
      hooks = hooks :+ (() => hook)
    }
    def shutdown(): Unit = {
      for (h <- hooks) h.apply()
    }

    def exit(message: String, cause: Option[Throwable] = None, returnCode: Int = -1): Nothing = {
      throw new ExitException(message, cause, returnCode)
    }
  }

  class FakeApplicationProvider(applicationPath: File) extends ApplicationProvider {
    val path = applicationPath
    def get = ???
  }

  val fakeAppProviderCtor = ((applicationPath: File) => new FakeApplicationProvider(applicationPath))

  class FakeServer(config: ServerConfig) extends Server with ServerWithStop {
    def applicationProvider = config.appProvider
    def mode = config.mode
    def mainAddress = ???
    override def stop() = {
      Play.stop()
      super.stop()
    }
  }

  "ServerStart" should {

    "not start without a path" in {
      val process = new FakeServerProcess()
      ServerStart.createServer(process, fakeAppProviderCtor) must throwAn(ExitException("No application path supplied"))
    }
    "not start without a process pid" in withTempDir { tempDir =>
      val process = new FakeServerProcess(
        args = Seq(tempDir.getAbsolutePath)
      )
      ServerStart.createServer(process, fakeAppProviderCtor) must throwAn(ExitException("Couldn't determine current process's pid"))
    }
    "not start if pid file already exists" in withTempDir { tempDir =>
      val process = new FakeServerProcess(
        args = Seq(tempDir.getAbsolutePath),
        pid = Some("123")
      )
      Files.write("x".getBytes, new File(tempDir, "RUNNING_PID"))
      ServerStart.createServer(process, fakeAppProviderCtor) must throwAn(ExitException(s"This application is already running (Or delete ${tempDir.getAbsolutePath}/RUNNING_PID file)."))
    }
    "not start without a port" in withTempDir { tempDir =>
      val process = new FakeServerProcess(
        args = Seq(tempDir.getAbsolutePath),
        propertyMap = Map("http.port" -> "disabled"),
        pid = Some("123")
      )
      ServerStart.createServer(process, fakeAppProviderCtor) must throwAn(ExitException("Must provide either an HTTP or HTTPS port"))
    }

    case class LaunchPlan[S <: ServerWithStop](
      val args: Seq[String] = Seq(),
      val basePropMap: Map[String, String] = Map.empty,
      val expectPidFile: Boolean = true,
      val pidFile: File,
      val expectedServerClass: Class[S] = classOf[NewNettyServer]
    )

    def launchWithTempDir(block: File => LaunchPlan[_]) = withTempDir { tempDir =>
      val launchPlan = block(tempDir)
      val process = new FakeServerProcess(
        args = launchPlan.args,
        propertyMap = launchPlan.basePropMap ++ Map("http.port" -> "29234"),
        pid = Some("123")
      )
      val server = ServerStart.createServer(process, fakeAppProviderCtor)
      try {
        server.getClass must_== launchPlan.expectedServerClass

        // Check pid file existence

        launchPlan.pidFile.exists must_== launchPlan.expectPidFile
        if (launchPlan.expectPidFile) {
          val ascii = Charset.forName("US-ASCII")
          Files.toString(launchPlan.pidFile, ascii) must_== "123"
        }

      } finally {
        server.stop()
        process.shutdown()
      }

      // Check pid file deleted on shutdown
      launchPlan.pidFile.exists must beFalse
    }

    "launch with path in args" in launchWithTempDir { tempDir =>
      LaunchPlan[NewNettyServer](
        args = Seq(tempDir.getAbsolutePath),
        pidFile = new File(tempDir, "RUNNING_PID")
      )
    }

    "launch with path in prop" in launchWithTempDir { tempDir =>
      val subdir = new File(tempDir, "appdir")
      subdir.mkdir()
      LaunchPlan[NewNettyServer](
        basePropMap = Map("user.dir" -> subdir.getAbsolutePath),
        pidFile = new File(subdir, "RUNNING_PID")
      )
    }

    "launch with explicit pidfile" in launchWithTempDir { tempDir =>
      val subdir = new File(tempDir, "appdir")
      subdir.mkdir()
      val pidFile = new File(tempDir, "mypid")
      LaunchPlan[NewNettyServer](
        args = Seq(subdir.getAbsolutePath),
        basePropMap = Map("pidfile.path" -> pidFile.getAbsolutePath),
        pidFile = pidFile
      )
    }

    "launch with no pidfile if set to /dev/null" in launchWithTempDir { tempDir =>
      LaunchPlan[NewNettyServer](
        args = Seq(tempDir.getAbsolutePath),
        basePropMap = Map("pidfile.path" -> "/dev/null"),
        expectPidFile = false,
        pidFile = new File(tempDir, "RUNNING_PID")
      )
    }

    "launch with a custom server" in launchWithTempDir { tempDir =>
      LaunchPlan[FakeServer](
        args = Seq(tempDir.getAbsolutePath),
        basePropMap = Map("server.class" -> classOf[FakeServer].getName),
        pidFile = new File(tempDir, "RUNNING_PID"),
        expectedServerClass = classOf[FakeServer]
      )
    }

  }

}