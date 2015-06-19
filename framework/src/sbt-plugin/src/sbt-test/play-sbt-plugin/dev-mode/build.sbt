lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := Option(System.getProperty("scala.version")).getOrElse("2.10.5")

PlayKeys.playInteractionMode := play.sbt.StaticPlayNonBlockingInteractionMode

// Start by using the sbt watcher
PlayKeys.fileWatchService := play.runsupport.FileWatchService.sbt(pollInterval.value)

TaskKey[Unit]("resetReloads") := {
  (target.value / "reload.log").delete()
}

InputKey[Unit]("verifyReloads") := {
  val expected = Def.spaceDelimited().parsed.head.toInt
  val actual = IO.readLines(target.value / "reload.log").count(_.nonEmpty)
  if (expected == actual) {
    println(s"Expected and got $expected reloads")
  } else {
    throw new RuntimeException(s"Expected $expected reloads but got $actual")
  }
}

InputKey[Unit]("verifyResourceContains") := {
  val args = Def.spaceDelimited("<path> <status> <words> ...").parsed
  val path = args.head
  val status = args.tail.head.toInt
  val assertions = args.tail.tail
  DevModeBuild.verifyResourceContains(path, status, assertions, 0)
}

PlayKeys.playReloadLeakHandler := Some(new Runnable {
  def run = {
    println("Memory leak handler")
    IO.write(target.value / "leak-detected", "Leak detected")
  }
})