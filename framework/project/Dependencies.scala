/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
import sbt._

object Dependencies {

  val specsVersion = "2.3.12"
  val specsBuild = Seq(
    "specs2-core",
    "specs2-junit",
    "specs2-mock",
    "specs2-matcher-extra"
  ).map("org.specs2" %% _ % specsVersion)
  val specsSbt = specsBuild

  val jacksons = Seq(
    "jackson-core",
    "jackson-annotations",
    "jackson-databind"
  ).map("com.fasterxml.jackson.core" % _ % "2.3.2")

  val guava = "com.google.guava" % "guava" % "16.0.1"
  val findBugs = "com.google.code.findbugs" % "jsr305" % "2.0.3" // Needed by guava
  val mockitoAll = "org.mockito" % "mockito-all" % "1.9.5"

  val h2database = "com.h2database" % "h2" % "1.3.175"

  val acolyteVersion = "1.0.22"
  val acolyte = "org.eu.acolyte" % "jdbc-driver" % acolyteVersion

  val jdbcDeps = Seq(
    "com.jolbox" % "bonecp" % "0.8.0.RELEASE",
    h2database,
    acolyte % Test,
    "tyrex" % "tyrex" % "1.0.1") ++ specsBuild.map(_ % Test)

  val javaJdbcDeps = Seq(acolyte % Test)

  val avajeEbeanormAgent = "org.avaje.ebeanorm" % "avaje-ebeanorm-agent" % "3.2.2" exclude ("javax.persistence", "persistence-api")
  val ebeanDeps = Seq(
    "org.avaje.ebeanorm" % "avaje-ebeanorm" % "3.3.4" exclude ("javax.persistence", "persistence-api"),
    avajeEbeanormAgent
  )

  val jpaDeps = Seq(
    "org.hibernate.javax.persistence" % "hibernate-jpa-2.0-api" % "1.0.1.Final",
    "org.hibernate" % "hibernate-entitymanager" % "3.6.9.Final" % "test"
  )

  val link = Seq(
    "org.javassist" % "javassist" % "3.18.2-GA"
  )
  val javassist = link

  val javaDeps = Seq(
    "org.yaml" % "snakeyaml" % "1.13",
    // 5.1.0 upgrade notes: need to add JEE dependencies, eg EL
    "org.hibernate" % "hibernate-validator" % "5.0.3.Final",

    ("org.springframework" % "spring-context" % "4.0.3.RELEASE" notTransitive ())
      .exclude("org.springframework", "spring-aop")
      .exclude("org.springframework", "spring-beans")
      .exclude("org.springframework", "spring-core")
      .exclude("org.springframework", "spring-expression")
      .exclude("org.springframework", "spring-asm"),

    ("org.springframework" % "spring-core" % "4.0.3.RELEASE" notTransitive ())
      .exclude("org.springframework", "spring-asm")
      .exclude("commons-logging", "commons-logging"),

    ("org.springframework" % "spring-beans" % "4.0.3.RELEASE" notTransitive ())
      .exclude("org.springframework", "spring-core"),

    ("org.reflections" % "reflections" % "0.9.8" notTransitive ())
      .exclude("javassist", "javassist"),

    guava,
    findBugs,

    "org.apache.tomcat" % "tomcat-servlet-api" % "8.0.5"
  ) ++ javassist ++ specsBuild.map(_ % Test)

  val junitInterface = "com.novocode" % "junit-interface" % "0.11"
  val junit = "junit" % "junit" % "4.11"

  val javaTestDeps = Seq(
    junit,
    junitInterface,
    "org.easytesting" % "fest-assert"     % "1.4",
    mockitoAll
  ).map(_ % Test)

  val jodatime = "joda-time" % "joda-time" % "2.3"
  val jodaConvert = "org.joda" % "joda-convert" % "1.6"

  val runtime = Seq("slf4j-api", "jul-to-slf4j", "jcl-over-slf4j").map("org.slf4j" % _ % "1.7.6") ++
    Seq("logback-core", "logback-classic").map("ch.qos.logback" % _ % "1.1.1") ++
    Seq("akka-actor", "akka-slf4j").map("com.typesafe.akka" %% _ % "2.3-SNAPSHOT") ++
    jacksons ++
    Seq(
      "org.scala-stm" %% "scala-stm" % "0.7",
      "commons-codec" % "commons-codec" % "1.9",

      jodatime,
      jodaConvert,

      "org.apache.commons" % "commons-lang3" % "3.3.2",

      "xerces" % "xercesImpl" % "2.11.0",

      "javax.transaction" % "jta" % "1.1",

      // Since we don't use any of the AOP features of guice, we exclude cglib.
      // This solves issues later where cglib depends on an older version of asm,
      // and other libraries (pegdown) depend on a newer version with a different groupId,
      // and this causes binary issues.
      "com.google.inject" % "guice" % "3.0" exclude("org.sonatype.sisu.inject", "cglib"),

      guava % Test,

      "org.scala-lang" % "scala-reflect" % BuildSettings.buildScalaVersion
    ) ++
    specsBuild.map(_ % Test) ++
    javaTestDeps

  val netty = Seq(
    "io.netty"           % "netty"                 % "3.9.3.Final",
    "com.typesafe.netty" % "netty-http-pipelining" % "1.1.2"
  )

  val akkaHttp = Seq(
    "com.typesafe.akka" %% "akka-http-core-experimental" % "0.6-SNAPSHOT"
  )

  val routersCompilerDependencies =  Seq(
    "commons-io" % "commons-io" % "2.0.1"
  ) ++ specsBuild.map(_ % Test)

  private def sbtPluginDep(moduleId: ModuleID) = {
    moduleId.extra(
      "sbtVersion" -> BuildSettings.buildSbtVersionBinaryCompatible,
      "scalaVersion" -> BuildSettings.buildScalaBinaryVersionForSbt
    )
  }

  val typesafeConfig = "com.typesafe" % "config" % "1.2.1"

  val sbtDependencies = Seq(
    "org.scala-lang" % "scala-reflect" % BuildSettings.buildScalaVersionForSbt % "provided",
    typesafeConfig,
    "org.mozilla" % "rhino" % "1.7R4",

    ("com.google.javascript" % "closure-compiler" % "v20130603")
      .exclude("args4j", "args4j")
      .exclude("com.google.protobuf", "protobuf-java")
      .exclude("com.google.code.findbugs", "jsr305"),

    guava,

    avajeEbeanormAgent,

    h2database,

    "net.contentobjects.jnotify" % "jnotify" % "0.94",

    sbtPluginDep("com.typesafe.sbt" % "sbt-twirl" % "1.0.2"),
    sbtPluginDep("com.typesafe.sbt" % "sbt-play-enhancer" % "1.0.1"),

    sbtPluginDep("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.5.0"),
    sbtPluginDep("com.github.mpeltonen" % "sbt-idea" % "1.5.1"),
    sbtPluginDep("com.typesafe.sbt" % "sbt-native-packager" % "0.7.4"),

    sbtPluginDep("com.typesafe.sbt" % "sbt-web" % "1.1.0"),
    sbtPluginDep("com.typesafe.sbt" % "sbt-js-engine" % "1.0.1"),
    sbtPluginDep("com.typesafe.sbt" % "sbt-webdriver" % "1.0.0")
  ) ++ javassist ++ specsBuild.map(_ % Test)

  val playDocsDependencies = Seq(
    "com.typesafe.play" %% "play-doc" % "1.2.0",
    "org.webjars" % "jquery"   % "2.1.0-2"    % "webjars",
    "org.webjars" % "prettify" % "4-Mar-2013" % "webjars"
  )

  val iterateesDependencies = Seq(
    "org.scala-stm" %% "scala-stm" % "0.7",
    typesafeConfig
  ) ++ specsBuild.map(_ % Test)

  val streamsDependencies = Seq(
    "org.reactivestreams" % "reactive-streams" % "0.4.0.M2"
  ) ++ specsBuild.map(_ % "test")

  val jsonDependencies = Seq(
    jodatime,
    jodaConvert,
    "org.scala-lang" % "scala-reflect" % BuildSettings.buildScalaVersion) ++
  jacksons ++
  specsBuild.map(_ % Test)

  val scalacheckDependencies = Seq(
    "org.specs2"     %% "specs2-scalacheck" % specsVersion % Test,
    "org.scalacheck" %% "scalacheck"        % "1.11.3"     % Test
  )

  val playServerDependencies = Seq(
    guava % Test
  ) ++ specsBuild.map(_ % Test)

  val testDependencies = Seq(junit) ++ specsBuild ++ Seq(
    junitInterface,
    guava,
    findBugs,
    ("org.fluentlenium" % "fluentlenium-core" % "0.10.2")
      .exclude("org.jboss.netty", "netty")
  )

  val integrationTestDependencies = scalacheckDependencies ++ Seq(
    "org.databene" % "contiperf" % "2.2.0" % Test
  )

  val playCacheDeps = "net.sf.ehcache" % "ehcache-core" % "2.6.8" +:
    specsBuild.map(_ % Test)

  val playWsDeps = Seq(
    guava,
    "com.ning" % "async-http-client" % "1.8.8"
  ) ++ Seq("signpost-core", "signpost-commonshttp4").map("oauth.signpost" % _  % "1.2.1.2") ++
  specsBuild.map(_ % Test) :+
  mockitoAll % Test

  val anormDependencies = specsBuild.map(_ % Test) ++ Seq(
    "com.jsuereth" %% "scala-arm" % "1.4",
    h2database % Test,
    "org.eu.acolyte" %% "jdbc-scala" % acolyteVersion % Test,
    jodatime,
    jodaConvert,
    "com.chuusai" % "shapeless" % "2.0.0" % Test cross CrossVersion.binaryMapped {
      case "2.10" => BuildSettings.buildScalaVersion
      case x => x
    }
  )

  val playDocsSbtPluginDependencies = Seq(
    "com.typesafe.play" %% "play-doc" % "1.2.0"
  )

}
