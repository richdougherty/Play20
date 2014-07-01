/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server

import com.google.common.io.Files
import java.io.File
import java.nio.charset.Charset
import java.util.Properties
import org.specs2.mutable.Specification
import play.core.ApplicationProvider

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

  // class TestApplication(application: Application) extends ApplicationProvider {

  //   Play.start(application)

  //   def get = Success(application)
  //   def path = application.path
  // }

  class FakeApplicationProvider(applicationPath: File) extends ApplicationProvider {
    val path = applicationPath
    def get = ???
  }

  val fakeAppProviderCtor = ((applicationPath: File) => new FakeApplicationProvider(applicationPath))

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

    def launchWithTempDir(block: File => (Seq[String], Map[String,String],Boolean,File)) = withTempDir { tempDir =>
      val (args, basePropMap, expectPidFile, pidFile) = block(tempDir)
      val process = new FakeServerProcess(
        args = args,
        propertyMap = basePropMap ++ Map("http.port" -> "29234"),
        pid = Some("123")
      )
      val server = ServerStart.createServer(process, fakeAppProviderCtor)
      try {
        server must not beNull

        // Check pid file exists

        pidFile.exists must_== expectPidFile
        if (expectPidFile) {
          val ascii = Charset.forName("US-ASCII")
          Files.toString(pidFile, ascii) must_== "123"
        }

      } finally {
        server.stop()
        process.shutdown()
      }

      // Check pid file deleted on shutdown
      pidFile.exists must beFalse
    }

    "launch with path in args" in launchWithTempDir { tempDir =>
      (
        Seq(tempDir.getAbsolutePath),
        Map.empty,
        true,
        new File(tempDir, "RUNNING_PID")
      )
    }

    "launch with path in prop" in launchWithTempDir { tempDir =>
      val subdir = new File(tempDir, "appdir")
      subdir.mkdir()
      (
        Seq(),
        Map("user.dir" -> subdir.getAbsolutePath),
        true,
        new File(subdir, "RUNNING_PID")
      )
    }

    "launch with explicit pidfile" in launchWithTempDir { tempDir =>
      val subdir = new File(tempDir, "appdir")
      subdir.mkdir()
      val pidFile = new File(tempDir, "mypid")
      (
        Seq(subdir.getAbsolutePath),
        Map("pidfile.path" -> pidFile.getAbsolutePath),
        true,
        pidFile
      )
    }

    "launch with no pidfile if set to /dev/null" in launchWithTempDir { tempDir =>
      (
        Seq(tempDir.getAbsolutePath),
        Map("pidfile.path" -> "/dev/null"),
        false,
        new File(tempDir, "RUNNING_PID")
      )
    }

  }

}