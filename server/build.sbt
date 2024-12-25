//import sbt.Keys.scalaVersion
import sbtassembly.AssemblyPlugin.autoImport.assemblyMergeStrategy
import com.google.cloud.tools.jib.api.buildplan.Platform
import de.gccc.jib.MappingsHelper

// --- Dependencies

val excludeLog4j = ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl")
val excludeScalaJs = List(
  ExclusionRule("org.scala-lang", "scala3-library_sjs"),
  ExclusionRule("org.scala-lang", "scala3-library_sjs1_3"),
  ExclusionRule("org.scala-js", "scalajs-library_2.13")
)

val circeVersion    = "0.14.10"
val http4sVersion   = "1.0.0-M44"

val bouncyCastle = "org.apache.directory.studio" % "org.bouncycastle.bcprov.jdk15" % "140"

val circe                    = "io.circe"                 %% "circe-core"                 % circeVersion
val circeGeneric             = "io.circe"                 %% "circe-generic"              % circeVersion
val circeParser              = "io.circe"                 %% "circe-parser"               % circeVersion

val slick                    = "com.typesafe.slick"       %% "slick"                      % "3.5.2"
val slickHikariCp            = "com.typesafe.slick"       %% "slick-hikaricp"             % "3.5.2"

val jwtCirce                 = "com.github.jwt-scala"     %% "jwt-circe"                  % "10.0.1"
val slf4jApi                 = "org.slf4j"                 % "slf4j-api"                  % "2.0.16"

val scribe                   = "com.outr"                 %% "scribe"                     % "3.15.3"
val scribeSlf4j              = "com.outr"                 %% "scribe-slf4j"               % "3.15.3"

val fs2Core                  = "co.fs2"                   %% "fs2-core"                   % "3.11.0"
val fs2Io                    = "co.fs2"                   %% "fs2-io"                     % "3.11.0"
val catsEffect               = "org.typelevel"            %% "cats-effect"                % "3.5.7"

val scalaTest                = "org.scalatest"            %% "scalatest"                  % "3.2.19"           % Test
val scalaTestCheck           = "org.scalatestplus"        %% "scalacheck-1-15"            % "3.2.11.0"         % Test

val hsqlDB                   = "org.hsqldb"                % "hsqldb"                     % "2.7.4"
val h2DB                     = "com.h2database"            % "h2"                         % "2.3.232"

val pureConfig               = "com.github.pureconfig"    %% "pureconfig-core"            % "0.17.8"
val pureConfigGeneric        = "com.github.pureconfig"    %% "pureconfig-generic-scala3"  % "0.17.8"
val typesafeConfig           = "com.typesafe"              % "config"                     % "1.4.3"

val liquibaseCore            = "org.liquibase"             % "liquibase-core"             % "4.30.0"

val solr                     = "org.apache.solr"           % "solr-core"                  % "8.11.1" excludeAll(excludeLog4j)
val solrLangId               = "org.apache.solr"           % "solr-langid"                % "8.11.1" excludeAll(excludeLog4j)

val scalaPbRuntimeGrcp       = "com.thesamet.scalapb"     %% "scalapb-runtime-grpc"       % scalapb.compiler.Version.scalapbVersion
val scalaPbRuntimeProtobuf   = "com.thesamet.scalapb"     %% "scalapb-runtime"            % scalapb.compiler.Version.scalapbVersion % "protobuf"
val scalaPbRuntime           = "com.thesamet.scalapb"     %% "scalapb-runtime"            % scalapb.compiler.Version.scalapbVersion

val log4CatsSlf4j            = "org.typelevel"            %% "log4cats-slf4j"             % "2.7.0"


val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % http4sVersion
val http4sEmberClient = "org.http4s" %% "http4s-ember-client" % http4sVersion
val http4sDsl         = "org.http4s" %% "http4s-dsl"          % http4sVersion
val http4sCirce       = "org.http4s" %% "http4s-circe"        % http4sVersion

val javaOpts = Seq("-DAMONY_SOLR_DELETE_LOCKFILE_ONSTARTUP=true", "-DAMONY_SECURE_COOKIES=false")

//fork in Global := true
cancelable in Global := true

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
        scribe,
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
        scribe,
        fs2Core,
        fs2Io,
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
        scribe,
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
        jwtCirce, bouncyCastle,
        circe, circeGeneric, pureConfig, pureConfigGeneric, slick,
        scalaPbRuntimeGrcp, scalaPbRuntimeProtobuf,
        http4sDsl, http4sCirce
      )
    )

lazy val resources =
  module("resources")
    .dependsOn(libFFMPeg, libEventStore, libFiles, identity)
    .settings(protobufSettings)
    .settings(
      name := "amony-service-resources",
      libraryDependencies ++= Seq(
        scribe,
        circe, circeGeneric, http4sCirce,
        scalaTest,
        slick, fs2Core, fs2Io, http4sDsl, liquibaseCore,
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
        catsEffect,
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
      outputStrategy         := Some(StdoutOutput),

      jibBaseImage            := "europe-west4-docker.pkg.dev/amony-04c85b/docker-images/amony/base:latest",
      jibRegistry             := "europe-west4-docker.pkg.dev",
      jibName                 := "amony-app",
      jibCustomRepositoryPath := Some("amony-04c85b/docker-images/amony/" + jibName.value),
      jibPlatforms            := Set(new Platform("amd64", "linux")),
      jibImageFormat          := JibImageFormat.Docker,
      jibTags                 := List("latest"),
      jibExtraMappings   ++= {
        // this adds the frontend assets to the docker image
        val webClientDir = (Compile / baseDirectory).value / ".." / ".." / "web-client" / "dist"
        val target = "/app/assets"
        MappingsHelper.contentOf(webClientDir, target)
      },
      jibEnvironment := Map(
        "JAVA_TOOL_OPTIONS"     -> "-Dconfig.file=/app/resources/application.conf",
        "AMONY_HOME"            -> "/app",
        "AMONY_WEB_CLIENT_PATH" -> "/app/assets",
        "AMONY_MEDIA_PATH"      -> "/media"
      ),
      jibUseCurrentTimestamp := true,

      Compile / packageBin / mainClass := Some("nl.amony.webserver.Main"),

      libraryDependencies ++= Seq(
        // logging
        scribeSlf4j, log4CatsSlf4j,
        // config loading
        typesafeConfig, pureConfig,
        // database
        slickHikariCp, hsqlDB, h2DB,
        fs2Core,
        http4sEmberServer,
        // test
        scalaTest, scalaTestCheck
      ),

      excludeDependencies ++= List(
        ExclusionRule("javax.xml.bind", "jaxb-api"),
      ),
    )

lazy val amony = project
  .in(file("."))
  .settings(noPublishSettings)
  .settings(
    Global / cancelable   := true,
  )
  .disablePlugins(RevolverPlugin)
  .aggregate(libEventStore, libFFMPeg, libFiles, identity, searchService, solrSearch, amonyServer)