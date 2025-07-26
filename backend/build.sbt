//import sbt.Keys.scalaVersion
import sbtassembly.AssemblyPlugin.autoImport.assemblyMergeStrategy
import com.google.cloud.tools.jib.api.buildplan.Platform
import de.gccc.jib.MappingsHelper
import scala.sys.process._
import sbt.Keys.streams

def isMainBranch: Boolean = {
  val currentBranch = "git rev-parse --abbrev-ref HEAD".!!.trim
  currentBranch == "main"
}

def hasNoLocalChanges: Boolean = {
  val status = "git status --porcelain".!!
  status.isEmpty
}

// --- Dependencies

val circeVersion    = "0.14.14"
val http4sVersion   = "0.23.30"

val bouncyCastle = "org.apache.directory.studio" % "org.bouncycastle.bcprov.jdk15" % "140"

val jsoup  = "org.jsoup" % "jsoup" % "1.21.1"

val circe                    = "io.circe"                 %% "circe-core"                 % circeVersion
val circeGeneric             = "io.circe"                 %% "circe-generic"              % circeVersion
val circeParser              = "io.circe"                 %% "circe-parser"               % circeVersion

val skunkCore                = "org.tpolecat"             %% "skunk-core"                 % "1.0.0-M10"
val skunkCirce               = "org.tpolecat"             %% "skunk-circe"                % "1.0.0-M10"

val sqids                    = "org.sqids" %% "sqids" % "0.6.0"

val tapirCore                = "com.softwaremill.sttp.tapir"   %% "tapir-core"              % "1.11.40"
val tapirHttp4s              = "com.softwaremill.sttp.tapir"   %% "tapir-http4s-server"     % "1.11.40"
val tapirCatsEffect          = "com.softwaremill.sttp.tapir"   %% "tapir-cats-effect"       % "1.11.40"
val tapirSwaggerUI           = "com.softwaremill.sttp.tapir"   %% "tapir-swagger-ui-bundle" % "1.11.40"
val tapirCirce               = "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"        % "1.11.40"
val tapirCirceYamlSpec       = "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml"      % "0.11.10"

val tapirSharedFs2           = "com.softwaremill.sttp.shared"  %% "fs2"                   % "1.5.0"

val jwtCirce                 = "com.github.jwt-scala"     %% "jwt-circe"                  % "11.0.2"
val slf4jApi                 = "org.slf4j"                 % "slf4j-api"                  % "2.0.17"

val scribe                   = "com.outr"                 %% "scribe"                     % "3.17.0"
val scribeSlf4j              = "com.outr"                 %% "scribe-slf4j"               % "3.17.0"

val tikaCore                 = "org.apache.tika"           % "tika-core"                  % "3.1.0"

val fs2Core                  = "co.fs2"                   %% "fs2-core"                   % "3.12.0"
val fs2Io                    = "co.fs2"                   %% "fs2-io"                     % "3.12.0"
val catsEffect               = "org.typelevel"            %% "cats-effect"                % "3.6.2"

val scalaTest                = "org.scalatest"            %% "scalatest"                  % "3.2.19"           % Test
val scalaTestCheck           = "org.scalatestplus"        %% "scalacheck-1-15"            % "3.2.11.0"         % Test

val hsqlDB                   = "org.hsqldb"                % "hsqldb"                     % "2.7.4"
val h2DB                     = "com.h2database"            % "h2"                         % "2.3.232"
val postgresDriver           = "org.postgresql"            % "postgresql"                 % "42.7.7"

val pureConfig               = "com.github.pureconfig"    %% "pureconfig-core"            % "0.17.9"
val pureConfigGeneric        = "com.github.pureconfig"    %% "pureconfig-generic-scala3"  % "0.17.9"
val typesafeConfig           = "com.typesafe"              % "config"                     % "1.4.4"

val liquibaseCore            = "org.liquibase"             % "liquibase-core"             % "4.33.0"

val solr                     = "org.apache.solr"           % "solr-core"                  % "8.11.1"
val solrLangId               = "org.apache.solr"           % "solr-langid"                % "8.11.1"

val scalaPbRuntimeGrcp       = "com.thesamet.scalapb"     %% "scalapb-runtime-grpc"       % scalapb.compiler.Version.scalapbVersion
val scalaPbRuntimeProtobuf   = "com.thesamet.scalapb"     %% "scalapb-runtime"            % scalapb.compiler.Version.scalapbVersion % "protobuf"
val scalaPbRuntime           = "com.thesamet.scalapb"     %% "scalapb-runtime"            % scalapb.compiler.Version.scalapbVersion

val log4CatsSlf4j            = "org.typelevel"            %% "log4cats-slf4j"             % "2.7.0"

val apacheCommonsCodec = "commons-codec" % "commons-codec" % "1.15"


val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % http4sVersion
val http4sEmberClient = "org.http4s" %% "http4s-ember-client" % http4sVersion
val http4sDsl         = "org.http4s" %% "http4s-dsl"          % http4sVersion
val http4sCirce       = "org.http4s" %% "http4s-circe"        % http4sVersion


//fork in Global := true
cancelable in Global := true

// -- Shared options

val commonSettings = Seq(
  organization := "nl.amony",
  scalaVersion := "3.3.6",
  excludeDependencies ++= List(
    ExclusionRule("org.scala-lang", "scala3-library_sjs"),
    ExclusionRule("org.scala-lang", "scala3-library_sjs1_3"),
    ExclusionRule("org.scala-js", "scalajs-library_2.13"),
    ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl")
  ),
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
        scalaTest,
        scalaPbRuntime,
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
        scalaTest,
        scalaPbRuntime,
        h2DB % "test"
      )
    )

lazy val auth =
  module("auth")
    .dependsOn(libFiles, libEventStore)
    .settings(protobufSettings)
    .settings(
      name := "amony-service-auth",
      libraryDependencies ++= Seq(
        // akka
        jwtCirce, bouncyCastle,
        tapirCore, tapirCatsEffect, tapirCirce, tapirHttp4s,
        circe, circeGeneric, pureConfig, pureConfigGeneric,
        scalaPbRuntimeGrcp, scalaPbRuntimeProtobuf,
        http4sDsl, http4sCirce
      )
    )

lazy val resources =
  module("resources")
    .dependsOn(libFFMPeg, libEventStore, libFiles, auth)
    .settings(protobufSettings)
    .settings(
      Test / fork := true,
      name := "amony-service-resources",
      libraryDependencies ++= Seq(
        scribe, sqids,
        circe, circeGeneric, http4sCirce, jsoup, tikaCore,
        tapirCore, tapirCatsEffect, tapirCirce, tapirHttp4s, fs2Io, fs2Core,
        http4sDsl, liquibaseCore,
        skunkCore, skunkCirce,
        scalaPbRuntimeGrcp, scalaPbRuntimeProtobuf,
        scalaTest,
        postgresDriver % "test",
        h2DB % "test",
        "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.41.8" % "test",
        "commons-codec" % "commons-codec"% "1.18.0" % "test",
        "org.scalacheck" %% "scalacheck" % "1.18.1" % "test"
      )
    )

lazy val buildSolrTarGz = taskKey[Seq[File]]("Creates the solr.tar.gz file")

lazy val searchService =
  module("service-search")
    .dependsOn(resources)
    .settings(protobufSettings)
    .settings(
      name := "amony-service-search",
      libraryDependencies ++= Seq(
        slf4jApi, scribeSlf4j,
        solr, solrLangId,
        circe, circeGeneric,
        http4sDsl, http4sCirce,
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

lazy val jibWriteDockerTagsFile = taskKey[File]("Creates the version.txt file")

val javaDevOpts = Seq("-DAMONY_SOLR_DELETE_LOCKFILE_ONSTARTUP=true", "-DAMONY_SECURE_COOKIES=false", "-DAMONY_MEDIA_PATH=../../media")

lazy val app =
  module("app", mainClass = true)
    .dependsOn(auth, resources, searchService)
    .settings(
      name := "amony-app",
      reStart / javaOptions ++= javaDevOpts,
      run / fork             := true,
      run / javaOptions     ++= javaDevOpts,
      outputStrategy         := Some(StdoutOutput),

      jibBaseImage            := "europe-west4-docker.pkg.dev/amony-04c85b/docker-images/amony/base:latest",
      jibRegistry             := "europe-west4-docker.pkg.dev",
      jibName                 := "amony-app",
      jibVersion              := version.value.replace('+', '-'), // + sign is not valid in a docker tag
      jibCustomRepositoryPath := Some("amony-04c85b/docker-images/amony/" + jibName.value),
      jibPlatforms            := Set(new Platform("amd64", "linux")),
      jibImageFormat          := JibImageFormat.Docker,
      jibTags                 := { if (isMainBranch && hasNoLocalChanges) List("latest") else List("dev") },
      jibExtraMappings   ++= {
        // this adds the frontend assets to the docker image
        val webClientDir = (Compile / baseDirectory).value / ".." / ".." / "frontend" / "dist"
        val target = "/app/assets"
        val contents = MappingsHelper.contentOf(webClientDir, target)
        val log = streams.value.log
        log.info(s"frontend assets file count: ${contents.size}")
        contents
      },
      jibEnvironment := Map(
        "JAVA_TOOL_OPTIONS"     -> "-Dconfig.file=/app/resources/application.conf",
        "AMONY_HOME"            -> "/app",
        "AMONY_WEB_CLIENT_PATH" -> "/app/assets",
        "AMONY_MEDIA_PATH"      -> "/media"
      ),
      jibUseCurrentTimestamp := true,

      // This is a hack to make to create a file with the same docker tags from the jib build to be able to push them
      jibWriteDockerTagsFile := {
        val versionFile = (Compile / baseDirectory).value / ".docker-tags.txt"
        val tags = jibTags.value :+ jibVersion.value
        IO.write(versionFile, tags.mkString("\n"))
        versionFile
      },

      Compile / packageBin / mainClass := Some("nl.amony.app.Main"),

      libraryDependencies ++= Seq(
        // logging
        scribeSlf4j, log4CatsSlf4j,
        // config loading
        typesafeConfig, pureConfig,
        // database
        postgresDriver,
        fs2Core,
        http4sEmberServer,
        tapirCore, tapirCatsEffect, tapirHttp4s,  tapirSharedFs2, tapirSwaggerUI, tapirCirceYamlSpec,
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
  .aggregate(libEventStore, libFFMPeg, libFiles, auth, resources, searchService, app)