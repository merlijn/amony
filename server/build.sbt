//import sbt.Keys.scalaVersion
import sbtassembly.AssemblyPlugin.autoImport.assemblyMergeStrategy

import java.nio.file.Path

// --- Dependencies


val excludeLog4j = ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl")
val excludeScalaJs = List(
  ExclusionRule("org.scala-lang", "scala3-library_sjs"),
  ExclusionRule("org.scala-lang", "scala3-library_sjs1_3"),
  ExclusionRule("org.scala-js", "scalajs-library_2.13")
)

val akkaVersion     = "2.7.0"
val akkaHttpVersion = "10.4.0"
val circeVersion    = "0.14.9"

val circe                    = "io.circe"                 %% "circe-core"                 % circeVersion
val circeGeneric             = "io.circe"                 %% "circe-generic"              % circeVersion
val circeParser              = "io.circe"                 %% "circe-parser"               % circeVersion

val slick                    = "com.typesafe.slick"       %% "slick"                      % "3.5.2"
val slickHikariCp            = "com.typesafe.slick"       %% "slick-hikaricp"             % "3.5.2"

val jwtCirce                 = "com.github.jwt-scala"     %% "jwt-circe"                  % "9.2.0"
val slf4jApi                 = "org.slf4j"                 % "slf4j-api"                  % "2.0.16"
val scribeSlf4j              = "com.outr"                 %% "scribe-slf4j"               % "3.15.2"

val fs2Core                  = "co.fs2"                   %% "fs2-core"                   % "3.10.2"
val fs2Io                    = "co.fs2"                   %% "fs2-io"                     % "3.10.2"
val catsEffect               = "org.typelevel"            %% "cats-effect"                % "3.5.4"

val scalaTest                = "org.scalatest"            %% "scalatest"                  % "3.2.19"           % Test
val scalaTestCheck           = "org.scalatestplus"        %% "scalacheck-1-15"            % "3.2.11.0"         % Test

val hsqlDB                   = "org.hsqldb"                % "hsqldb"                     % "2.7.3"
val h2DB                     = "com.h2database"            % "h2"                         % "2.2.224"
val flywayDbCore             = "org.flywaydb"              % "flyway-core"                % "10.15.2"

val pureConfig               = "com.github.pureconfig"    %% "pureconfig-core"            % "0.17.8"
val typesafeConfig           = "com.typesafe"              % "config"                     % "1.4.3"

//val betterFiles              = "com.github.pathikrit"     %% "better-files"               % "3.9.1"
//val directoryWatcher         = "io.methvin"                % "directory-watcher"          % "0.15.0"
val solr                     = "org.apache.solr"           % "solr-core"                  % "8.11.1" excludeAll(excludeLog4j)
val solrLangId               = "org.apache.solr"           % "solr-langid"                % "8.11.1" excludeAll(excludeLog4j)

val scalaPbRuntimeGrcp       = "com.thesamet.scalapb"     %% "scalapb-runtime-grpc"       % scalapb.compiler.Version.scalapbVersion
val scalaPbRuntimeProtobuf   = "com.thesamet.scalapb"     %% "scalapb-runtime"            % scalapb.compiler.Version.scalapbVersion % "protobuf"
val scalaPbRuntime           = "com.thesamet.scalapb"     %% "scalapb-runtime"            % scalapb.compiler.Version.scalapbVersion


val http4sVersion = "1.0.0-M40"

val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % http4sVersion
val http4sEmberClient = "org.http4s" %% "http4s-ember-client" % http4sVersion
val http4sDsl = "org.http4s" %% "http4s-dsl" % http4sVersion
val http4sCirce = "org.http4s" %% "http4s-circe" % http4sVersion

val embeddedKafka = "io.github.embeddedkafka" %% "embedded-kafka" % "3.8.0" // cross CrossVersion.for3Use2_13

//val tapirCirce = "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % "1.2.9"
//val tapir = "com.softwaremill.sttp.tapir" %% "tapir-core" % "1.2.9"
//val tarirHttp4s = "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % "1.2.9"

val javaOpts = Seq("-DAMONY_SOLR_DELETE_LOCKFILE_ONSTARTUP=true")

// -- Shared options

val commonSettings = Seq(
  organization := "nl.amony",
  scalaVersion := "3.3.4",
  excludeDependencies ++= excludeScalaJs,
  assembly / assemblyMergeStrategy := {
    case path if path.endsWith(".proto") => MergeStrategy.discard
    case x                               => (assembly / assemblyMergeStrategy).value.apply(x)
  }
)

lazy val noPublishSettings = Seq(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false
)

def protobufSettings = Seq(
  Compile / PB.targets := Seq(
    scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "scalapb"
  )
)

def module(name: String, mainClass: Boolean = false) = {
  val project = sbt.Project.apply(name, file(name))
    .settings(commonSettings: _*)

  if (!mainClass)
    project.disablePlugins(RevolverPlugin)
  else
    project
}

// --- Modules

lazy val libFiles =
  module("lib-files")
    .settings(
      name         := "amony-lib-filewatcher",
      libraryDependencies ++= Seq(
        pureConfig,
        scribeSlf4j,
        fs2Core,
        slick,
        scalaTest,
        scalaPbRuntime,
        slickHikariCp % "test",
        h2DB % "test"
      )
    )

lazy val libFFMPeg =
  module("lib-ffmpeg")
    .dependsOn(libFiles)
    .settings(
      name         := "amony-lib-ffmpeg",
      libraryDependencies ++= Seq(
        scribeSlf4j,
        fs2Core,
        scalaTest,
        circe,
        circeGeneric,
        circeParser,
      )
    )

lazy val libEventStore =
  module("lib-eventstore")
    .settings(
      name         := "amony-lib-eventstore",
      libraryDependencies ++= Seq(
        pureConfig,
        scribeSlf4j,
        fs2Core,
        slick,
        scalaTest,
        scalaPbRuntime,
        slickHikariCp % "test",
        h2DB % "test"
      )
    )

lazy val identity =
  module("identity")
    .dependsOn(libFiles, libEventStore)
    .settings(protobufSettings)
    .settings(
      name := "amony-service-auth",
      libraryDependencies ++= Seq(
        // akka
        jwtCirce,
        circe, circeGeneric, pureConfig, slick,
        scalaPbRuntimeGrcp, scalaPbRuntimeProtobuf,
        http4sDsl, http4sCirce
      )
    )

lazy val resources =
  module("resources")
    .dependsOn(libFFMPeg, libEventStore, libFiles)
    .settings(protobufSettings)
    .settings(
      name := "amony-service-resources",
      libraryDependencies ++= Seq(
        scribeSlf4j,
        circe, circeGeneric, http4sCirce,
        scalaTest,
        slick, fs2Core, fs2Io, http4sDsl,
        scalaPbRuntimeGrcp, scalaPbRuntimeProtobuf,
        slickHikariCp % "test",
        h2DB % "test"
      )
    )

lazy val searchService =
  module("search-api")
    .dependsOn(resources)
    .settings(protobufSettings)
    .settings(
      name := "amony-service-search-api",
      libraryDependencies ++= Seq(
        // akka
        scribeSlf4j,
        circe, circeGeneric,
        http4sDsl, http4sCirce
      ),
    )

lazy val buildSolrTarGz = taskKey[Seq[File]]("Creates the solr.tar.gz file")

lazy val solrSearch =
  module("solr-search")
    .dependsOn(searchService)
    .settings(
      name := "amony-service-search-solr",
      libraryDependencies ++= Seq(
        slf4jApi, scribeSlf4j,
        solr, solrLangId,
      ),
      buildSolrTarGz / fileInputs += (Compile / resourceDirectory).value.toGlob / "solr" / "**",
      buildSolrTarGz := {
        import scala.sys.process._

        val log = streams.value.log
        val sourceDir = (Compile / resourceDirectory).value / "solr"
        val targetFile = (Compile / resourceManaged).value / "solr.tar.gz"
        val hasChanges = buildSolrTarGz.inputFileChanges.hasChanges

        if (!sourceDir.exists) {
          log.error(s"Source directory does not exist: ${sourceDir.getAbsolutePath}")
          Seq.empty
        } else if (hasChanges || !targetFile.exists) {
          log.info(s"Generating solr tar at: ${targetFile.getAbsolutePath}")
          // Ensure parent directory exists
          IO.createDirectory(targetFile.getParentFile)

          // Create tar.gz file using system commands
          val tarCmd = s"tar -czf ${targetFile.getAbsolutePath} -C ${sourceDir.getAbsolutePath} ."
          tarCmd.!
          Seq(targetFile)
        } else {
          log.debug(s"Skipped generating solr tar")
          Seq(targetFile)
        }
      },
      Compile / resourceGenerators += buildSolrTarGz.taskValue
    )

lazy val amonyServer =
  module("web-server", mainClass = true)
    .dependsOn(identity, resources, searchService, solrSearch)
    .settings(
      name := "amony-web-server",
      reStart / javaOptions ++= javaOpts,
      run / fork             := true,
      run / javaOptions     ++= javaOpts,
      libraryDependencies ++= Seq(

        // logging
        slf4jApi, scribeSlf4j,

        // config loading
        typesafeConfig, pureConfig,
        flywayDbCore,
        slickHikariCp, hsqlDB,
        h2DB,
        circe,
        circeGeneric,
        circeParser,
        fs2Core,
        http4sEmberServer,
        // test
        scalaTest, scalaTestCheck
      ),
      //    assembly / logLevel := Level.Debug,
      assembly / assemblyJarName := "amony.jar",
      assembly / assemblyMergeStrategy := {
        case s if s.endsWith("module-info.class")            => MergeStrategy.discard
        case s if s.endsWith("Log4j2Plugins.dat")            => MergeStrategy.discard
        case s if s.startsWith("org/iq80/leveldb")           => MergeStrategy.first
        case s if s.endsWith("io.netty.versions.properties") => MergeStrategy.first
        case x =>
          val oldStrategy = (assembly / assemblyMergeStrategy).value
          oldStrategy(x)
      }
    )

lazy val amony = project
  .in(file("."))
  .settings(noPublishSettings)
  .settings(
    Global / cancelable   := true,
  )
  .disablePlugins(RevolverPlugin)
  .aggregate(libEventStore, libFFMPeg, libFiles, identity, searchService, solrSearch, amonyServer)