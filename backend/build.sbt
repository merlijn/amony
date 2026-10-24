import scala.concurrent.duration.*
import scala.sys.process.*

import de.gccc.jib.MappingsHelper
import sbt.Command
import sbt.Keys.{scalaVersion, streams}

val devScalacOptions = Seq(
  "-rewrite",
  "-source",
  "future",
  "-encoding",
  "utf-8"
)

val prodScalacOptions = Seq(
  "-rewrite",
  "-source",
  "future",
  "-encoding",
  "utf-8",
//  "-Wunused:all",
//  "-Xfatal-warnings"
//  "-deprecation",
  "-feature"
)

// CI sets JIB_RELEASE_BUILD=true on tag builds; locally we fall back to whether HEAD is a tag.
def isReleaseBuild: Boolean =
  sys.env.get("JIB_RELEASE_BUILD").flatMap(_.toBooleanOption).getOrElse("git tag --points-at HEAD".!!.trim.nonEmpty)

// CI can pin the exact image tags, e.g. "pr-123" for pull requests.
def envTags: Option[List[String]] =
  sys.env.get("JIB_TAGS").map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList).filter(_.nonEmpty)

// GHCR namespace (lowercase required by the registry); CI provides GITHUB_REPOSITORY_OWNER.
val ghcrOwner = sys.env.getOrElse("GITHUB_REPOSITORY_OWNER", "merlijn").toLowerCase

//fork in Global := true
Global / cancelable := true

// Shut down the detached sbt server after 1 hour of inactivity instead of the 7 day default.
Global / serverIdleTimeout := Some(1.hour)

// -- Custom tasks

lazy val buildSolrTarGz         = taskKey[Seq[File]]("Creates the solr.tar.gz file")
lazy val jibWriteDockerTagsFile = taskKey[File]("Creates the version.txt file")
lazy val exportDatabaseSchema   =
  taskKey[Unit]("Applies every migration to a throwaway PostgreSQL container and dumps the schema to target/amony-schema.sql")
lazy val prodBuild              = settingKey[Boolean]("Whether to compile with production scalac options")

ThisBuild / prodBuild := false

addCommandAlias(
  "format",
  "; platform/scalafmt; platform/Test/scalafmt; subprocess/scalafmt; subprocess/Test/scalafmt; database/scalafmt; database/Test/scalafmt; messagebus/scalafmt; messagebus/Test/scalafmt; auth/scalafmt; auth/Test/scalafmt; resources/scalafmt; resources/Test/scalafmt; search/scalafmt; search/Test/scalafmt; scalafmt; Test/scalafmt"
)
addCommandAlias("generateSpec", "runMain nl.amony.GenerateSpec")

inThisBuild(
  List(
    scalaVersion      := "3.9.0",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision
  )
)

commands += Command.command("prod") { state =>
  val settings = Seq(
    ThisBuild / prodBuild := true,
    scalaVersion          := "3.9.0",
    semanticdbEnabled     := true,
    semanticdbVersion     := scalafixSemanticdb.revision
  )

  Project.extract(state).appendWithSession(settings, state)
}

commands += Command.command("dev") { state =>
  Project.extract(state).appendWithSession(
    Seq(ThisBuild / prodBuild := false),
    state
  )
}

val javaDevOpts = Seq(
  // OTEL
  "-DAMONY_OTEL_ENABLED=false",
  "-Dotel.sdk.disabled=true",
  "-Dotel.service.name=amony-app",
//  "-Dotel.exporter.otlp.endpoint=http://localhost:5080/api/default",
//  "-Dotel.exporter.otlp.headers=Authorization=Basic <key>",
//  "-Dotel.exporter.otlp.protocol=http/protobuf",
  "-DAMONY_DEFAULT_MEDIA_PATH=../data/media",
  "-DAMONY_SOLR_PATH=../data/solr",
  "-DAMONY_WEB_CLIENT_PATH=../frontend/dist",
  // Auth
  "-DAMONY_AUTH_ENABLED=false",
  "-DAMONY_SECURE_COOKIES=false",
  "-DAMONY_IDP_AUTHORIZE_URL=http://localhost:5556/dex/auth",
  "-DAMONY_IDP_TOKEN_URL=http://localhost:5556/dex/token",
  "-DAMONY_IDP_USERINFO_URL=http://localhost:5556/dex/userinfo",
  "-DAMONY_JWT_SECRET_KEY=development-key"
)

// --- Main project

val circeVersion  = "0.14.17"
val http4sVersion = "0.23.38"
val tapirVersion  = "1.14.0"
val sttpVersion   = "4.1.0"
val otel4sVersion = "1.1.0"

val commonExclusions = List(
  ExclusionRule("org.scala-lang", "scala3-library_sjs"),
  ExclusionRule("org.scala-lang", "scala3-library_sjs1_3"),
  ExclusionRule("org.scala-js", "scalajs-library_2.13"),
  ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl"),
  ExclusionRule("javax.xml.bind", "jaxb-api")
)

lazy val commonSettings = Seq(
  organization  := "nl.amony",
  scalacOptions := (if (prodBuild.value) prodScalacOptions else devScalacOptions),
  Test / fork   := true,
  test / javaOptions ++= javaDevOpts,
  excludeDependencies ++= commonExclusions
)

val platformDependencies = Seq(
  "org.typelevel"                 %% "cats-effect"               % "3.7.1",
  "org.typelevel"                 %% "cats-mtl"                  % "1.7.0",
  "co.fs2"                        %% "fs2-core"                  % "3.14.0",
  "co.fs2"                        %% "fs2-io"                    % "3.14.0",
  "io.circe"                      %% "circe-core"                % circeVersion,
  "io.circe"                      %% "circe-parser"              % circeVersion,
  "com.outr"                      %% "scribe"                    % "3.19.0",
  "com.outr"                      %% "scribe-slf4j"              % "3.19.0",
  "org.typelevel"                 %% "otel4s-core"               % otel4sVersion,
  "org.typelevel"                 %% "otel4s-core-trace"         % otel4sVersion,
  "org.typelevel"                 %% "otel4s-oteljava"           % otel4sVersion,
  "org.typelevel"                 %% "otel4s-semconv"            % otel4sVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-core"                % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"          % tapirVersion,
  "com.softwaremill.sttp.shared"  %% "fs2"                       % "1.6.0",
  "com.softwaremill.sttp.client4" %% "core"                      % sttpVersion,
  "com.softwaremill.sttp.client4" %% "circe"                     % sttpVersion,
  "com.softwaremill.sttp.client4" %% "cats"                      % sttpVersion,
  "org.tpolecat"                  %% "skunk-core"                % "2.0.0-RC3",
  "org.tpolecat"                  %% "skunk-circe"               % "2.0.0-RC3",
  "com.github.pureconfig"         %% "pureconfig-core"           % "0.17.11",
  "com.github.pureconfig"         %% "pureconfig-generic-scala3" % "0.17.11"
)

val subprocessDependencies = Seq(
  "org.typelevel" %% "cats-effect"       % "3.7.1",
  "co.fs2"        %% "fs2-core"          % "3.14.0",
  "co.fs2"        %% "fs2-io"            % "3.14.0",
  "io.circe"      %% "circe-core"        % circeVersion,
  "io.circe"      %% "circe-generic"     % circeVersion,
  "io.circe"      %% "circe-parser"      % circeVersion,
  "com.outr"      %% "scribe"            % "3.19.0",
  "org.typelevel" %% "otel4s-core"       % otel4sVersion,
  "org.typelevel" %% "otel4s-core-trace" % otel4sVersion
)

val databaseDependencies = Seq(
  "org.typelevel"         %% "cats-effect"               % "3.7.1",
  "com.outr"              %% "scribe"                    % "3.19.0",
  "org.tpolecat"          %% "skunk-core"                % "2.0.0-RC3",
  "com.github.pureconfig" %% "pureconfig-core"           % "0.17.11",
  "com.github.pureconfig" %% "pureconfig-generic-scala3" % "0.17.11",
  "org.liquibase"          % "liquibase-core"            % "5.0.4",
  "org.postgresql"         % "postgresql"                % "42.7.14",
  "org.typelevel"         %% "otel4s-core"               % otel4sVersion,
  "org.typelevel"         %% "otel4s-core-trace"         % otel4sVersion
)

val databaseTestDependencies = Seq(
  "org.scalatest" %% "scalatest" % "3.2.20" % Test
)

val messageBusDependencies = Seq(
  "org.typelevel"         %% "cats-effect"               % "3.7.1",
  "co.fs2"                %% "fs2-core"                  % "3.14.0",
  "io.circe"              %% "circe-core"                % circeVersion,
  "com.outr"              %% "scribe"                    % "3.19.0",
  "org.tpolecat"          %% "skunk-core"                % "2.0.0-RC3",
  "org.tpolecat"          %% "skunk-circe"               % "2.0.0-RC3",
  "com.github.pureconfig" %% "pureconfig-core"           % "0.17.11",
  "com.github.pureconfig" %% "pureconfig-generic-scala3" % "0.17.11"
)

val messageBusTestDependencies = Seq(
  "org.scalatest" %% "scalatest"                      % "3.2.20"      % Test,
  "com.dimafeng"  %% "testcontainers-scala-scalatest" % "0.44.1"      % Test,
  "org.liquibase"  % "liquibase-core"                 % "5.0.4"       % Test,
  "org.postgresql" % "postgresql"                     % "42.7.14"     % Test,
  "org.typelevel" %% "otel4s-core"                    % otel4sVersion % Test,
  "org.typelevel" %% "otel4s-core-trace"              % otel4sVersion % Test
)

val authDependencies = Seq(
  "org.typelevel"                 %% "cats-effect"               % "3.7.1",
  "co.fs2"                        %% "fs2-core"                  % "3.14.0",
  "io.circe"                      %% "circe-core"                % circeVersion,
  "io.circe"                      %% "circe-parser"              % circeVersion,
  "com.outr"                      %% "scribe"                    % "3.19.0",
  "com.softwaremill.sttp.tapir"   %% "tapir-core"                % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"          % tapirVersion,
  "com.softwaremill.sttp.shared"  %% "fs2"                       % "1.6.0",
  "com.softwaremill.sttp.client4" %% "core"                      % sttpVersion,
  "com.softwaremill.sttp.client4" %% "circe"                     % sttpVersion,
  "com.softwaremill.sttp.client4" %% "cats"                      % sttpVersion,
  "org.tpolecat"                  %% "skunk-core"                % "2.0.0-RC3",
  "org.tpolecat"                  %% "skunk-circe"               % "2.0.0-RC3",
  "com.github.pureconfig"         %% "pureconfig-core"           % "0.17.11",
  "com.github.pureconfig"         %% "pureconfig-generic-scala3" % "0.17.11",
  "com.github.jwt-scala"          %% "jwt-circe"                 % "11.0.4",
  "org.bouncycastle"               % "bcprov-jdk18on"            % "1.85.2"
)

val resourcesDependencies = Seq(
  "org.typelevel"                 %% "cats-effect"               % "3.7.1",
  "co.fs2"                        %% "fs2-core"                  % "3.14.0",
  "co.fs2"                        %% "fs2-io"                    % "3.14.0",
  "io.circe"                      %% "circe-core"                % circeVersion,
  "io.circe"                      %% "circe-generic"             % circeVersion,
  "io.circe"                      %% "circe-parser"              % circeVersion,
  "com.outr"                      %% "scribe"                    % "3.19.0",
  "com.outr"                      %% "scribe-slf4j"              % "3.19.0",
  "org.typelevel"                 %% "otel4s-core"               % otel4sVersion,
  "org.typelevel"                 %% "otel4s-core-trace"         % otel4sVersion,
  "org.typelevel"                 %% "otel4s-oteljava"           % otel4sVersion,
  "org.typelevel"                 %% "otel4s-semconv"            % otel4sVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-core"                % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-http4s-server"       % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-cats-effect"         % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"          % tapirVersion,
  "org.http4s"                    %% "http4s-ember-server"       % http4sVersion,
  "org.http4s"                    %% "http4s-dsl"                % http4sVersion,
  "org.http4s"                    %% "http4s-circe"              % http4sVersion,
  "org.tpolecat"                  %% "skunk-core"                % "2.0.0-RC3",
  "org.tpolecat"                  %% "skunk-circe"               % "2.0.0-RC3",
  "com.github.pureconfig"         %% "pureconfig-core"           % "0.17.11",
  "com.github.pureconfig"         %% "pureconfig-generic-scala3" % "0.17.11",
  "com.softwaremill.sttp.client4" %% "core"                      % sttpVersion,
  "com.softwaremill.sttp.client4" %% "circe"                     % sttpVersion,
  "com.softwaremill.sttp.client4" %% "cats"                      % sttpVersion,
  "org.apache.tika"                % "tika-core"                 % "3.3.1",
  "org.jsoup"                      % "jsoup"                     % "1.23.2",
  "com.softwaremill.sttp.shared"  %% "fs2"                       % "1.6.0"
)

val searchDependencies = Seq(
  "org.typelevel"               %% "cats-effect"               % "3.7.1",
  "co.fs2"                      %% "fs2-core"                  % "3.14.0",
  "io.circe"                    %% "circe-core"                % circeVersion,
  "io.circe"                    %% "circe-generic"             % circeVersion,
  "io.circe"                    %% "circe-parser"              % circeVersion,
  "com.outr"                    %% "scribe"                    % "3.19.0",
  "com.softwaremill.sttp.tapir" %% "tapir-core"                % tapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-json-circe"          % tapirVersion,
  "com.github.pureconfig"       %% "pureconfig-core"           % "0.17.11",
  "com.github.pureconfig"       %% "pureconfig-generic-scala3" % "0.17.11",
  "org.apache.solr"              % "solr-core"                 % "9.10.1",
  "org.apache.commons"           % "commons-compress"          % "1.28.0"
)

val appDependencies = Seq(
  "org.typelevel"                 %% "cats-effect"                               % "3.7.1",
  "co.fs2"                        %% "fs2-core"                                  % "3.14.0",
  "co.fs2"                        %% "fs2-io"                                    % "3.14.0",
  "com.typesafe"                   % "config"                                    % "1.4.9",
  "com.github.pureconfig"         %% "pureconfig-core"                           % "0.17.11",
  "com.github.pureconfig"         %% "pureconfig-generic-scala3"                 % "0.17.11",
  "org.tpolecat"                  %% "skunk-core"                                % "2.0.0-RC3",
  "org.http4s"                    %% "http4s-ember-server"                       % http4sVersion,
  "org.http4s"                    %% "http4s-dsl"                                % http4sVersion,
  "org.http4s"                    %% "http4s-circe"                              % http4sVersion,
  "org.http4s"                    %% "http4s-otel4s-middleware-core"             % "0.19.0",
  "org.http4s"                    %% "http4s-otel4s-middleware-metrics"          % "0.19.0",
  "io.opentelemetry"               % "opentelemetry-exporter-otlp"               % "1.61.0" % Runtime,
  "io.opentelemetry"               % "opentelemetry-sdk-extension-autoconfigure" % "1.61.0" % Runtime,
  "org.typelevel"                 %% "otel4s-core"                               % otel4sVersion,
  "org.typelevel"                 %% "otel4s-core-trace"                         % otel4sVersion,
  "org.typelevel"                 %% "otel4s-oteljava"                           % otel4sVersion,
  "org.typelevel"                 %% "otel4s-semconv"                            % otel4sVersion,
  "org.typelevel"                 %% "log4cats-slf4j"                            % "2.8.0",
  "com.outr"                      %% "scribe"                                    % "3.19.0",
  "com.outr"                      %% "scribe-slf4j"                              % "3.19.0",
  "com.softwaremill.sttp.tapir"   %% "tapir-core"                                % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-http4s-server"                       % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-cats-effect"                         % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-swagger-ui-bundle"                   % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"                          % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-otel4s-tracing"                      % tapirVersion,
  "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml"                        % "0.12.0",
  "com.softwaremill.sttp.shared"  %% "fs2"                                       % "1.6.0",
  "com.softwaremill.sttp.client4" %% "core"                                      % sttpVersion,
  "com.softwaremill.sttp.client4" %% "circe"                                     % sttpVersion,
  "com.softwaremill.sttp.client4" %% "cats"                                      % sttpVersion,
  "com.comcast"                   %% "ip4s-core"                                 % "3.7.0",
  "org.slf4j"                      % "slf4j-api"                                 % "2.0.20"
)

val testDependencies = Seq(
  "org.scalatest"               %% "scalatest"                      % "3.2.20"     % Test,
  "org.scalatestplus"           %% "scalacheck-1-15"                % "3.2.11.0"   % Test,
  "com.dimafeng"                %% "testcontainers-scala-scalatest" % "0.44.1"     % Test,
  "commons-codec"                % "commons-codec"                  % "1.23.0"     % Test,
  "org.scalacheck"              %% "scalacheck"                     % "1.20.0"     % Test,
  "com.softwaremill.sttp.tapir" %% "tapir-sttp-stub4-server"        % tapirVersion % Test,
  "org.mockito"                 %% "mockito-scala-scalatest"        % "2.2.3"      % Test
)

lazy val platform = project
  .in(file("platform"))
  .settings(commonSettings)
  .settings(
    name := "platform",
    libraryDependencies ++= platformDependencies
  )

lazy val subprocess = project
  .in(file("platform/subprocess"))
  .dependsOn(platform)
  .settings(commonSettings)
  .settings(
    name := "subprocess",
    libraryDependencies ++= subprocessDependencies
  )

lazy val database = project
  .in(file("platform/database"))
  .settings(commonSettings)
  .settings(
    name := "database",
    libraryDependencies ++= databaseDependencies ++ databaseTestDependencies
  )

lazy val messagebus = project
  .in(file("platform/messagebus"))
  .dependsOn(database % "test->compile")
  .settings(commonSettings)
  .settings(
    name := "messagebus",
    libraryDependencies ++= messageBusDependencies ++ messageBusTestDependencies
  )

lazy val auth = project
  .in(file("modules/auth"))
  .dependsOn(platform)
  .settings(commonSettings)
  .settings(
    name := "auth",
    libraryDependencies ++= authDependencies
  )

lazy val resources = project
  .in(file("modules/resources"))
  .dependsOn(platform, subprocess, messagebus, auth)
  .settings(commonSettings)
  .settings(
    name := "resources",
    libraryDependencies ++= resourcesDependencies
  )

lazy val search = project
  .in(file("modules/search"))
  .dependsOn(resources, auth)
  .settings(commonSettings)
  .settings(
    name := "search",
    libraryDependencies ++= searchDependencies
  )

lazy val amony = project
  .in(file("."))
  .aggregate(platform, subprocess, database, messagebus, auth, resources, search)
  .dependsOn(platform, subprocess, database, messagebus, auth, resources, search)
  .settings(commonSettings)
  .settings(
    name                               := "app",
    Global / cancelable                := true,
    run / fork                         := true,
    run / javaOptions ++= javaDevOpts,
    outputStrategy                     := Some(StdoutOutput),
    Compile / run / mainClass          := Some("nl.amony.App"),
    Compile / packageBin / mainClass   := Some("nl.amony.App"),
    run / aggregate                    := false,
    runMain / aggregate                := false,
    jibDockerBuild / aggregate         := false,
    jibWriteDockerTagsFile / aggregate := false,
    exportDatabaseSchema / aggregate   := false,

    // Jib Docker settings
    jibBaseImage            := s"ghcr.io/$ghcrOwner/amony-base:latest",
    jibRegistry             := "ghcr.io",
    jibName                 := "amony-app",
    jibVersion              := version.value.replace('+', '-'), // + sign is not valid in a docker tag
    jibCustomRepositoryPath := Some(s"$ghcrOwner/" + jibName.value),
    jibPlatforms            := Set(if (System.getProperty("os.arch") == "aarch64") JibPlatforms.arm64 else JibPlatforms.amd64),
    jibImageFormat          := JibImageFormat.OCI,
    jibTags                 := envTags.getOrElse(if (isReleaseBuild) List("latest") else List("dev")),
    jibExtraMappings ++= Def.uncached {
      // this adds the frontend assets to the docker image
      val webClientDir = (Compile / baseDirectory).value / ".." / "frontend" / "dist"
      val target       = "/app/assets"
      val contents     = MappingsHelper.contentOf(webClientDir, target)
      val log          = streams.value.log
      log.info(s"frontend assets file count: ${contents.size}")
      contents
    },
    jibEnvironment          := Map(
      "JAVA_TOOL_OPTIONS"        -> "-Dconfig.file=/app/resources/application.conf",
      "AMONY_WEB_CLIENT_PATH"    -> "/app/assets",
      "AMONY_SOLR_PATH"          -> "/app/data/solr",
      "AMONY_DEFAULT_MEDIA_PATH" -> "/media",
      "OTEL_SERVICE_NAME"        -> "amony-app"
    ),
    jibUseCurrentTimestamp  := true,

    // Solr tar.gz generation
    buildSolrTarGz := Def.uncached {
      import scala.sys.process._

      val log        = streams.value.log
      val sourceDir  = (Compile / resourceDirectory).value / "solr"
      val targetFile = (Compile / resourceManaged).value / "solr.tar.gz"
      if (!sourceDir.exists) {
        log.error(s"Source directory does not exist: ${sourceDir.getAbsolutePath}")
        Seq.empty
      } else {
        log.info(s"Generating solr tar at: ${targetFile.getAbsolutePath}")
        // Ensure parent directory exists
        IO.createDirectory(targetFile.getParentFile)

        // Create tar.gz file using system commands
        val tarCmd = s"tar -czf ${targetFile.getAbsolutePath} -C ${sourceDir.getAbsolutePath} ."
        tarCmd.!
        Seq(targetFile)
      }
    },
    Compile / resourceGenerators += buildSolrTarGz.taskValue,

    // Dump the migrated database schema, grounded in the real Liquibase migrations.
    exportDatabaseSchema := Def.uncached {
      (Test / runMain).toTask(" nl.amony.ExportDatabaseSchema").value
    },

    // This is a hack to make to create a file with the same docker tags from the jib build to be able to push them
    jibWriteDockerTagsFile := Def.uncached {
      val versionFile = (Compile / baseDirectory).value / ".docker-tags.txt"
      // CI-pinned tags win (e.g. "pr-123"); otherwise release builds publish "latest" plus the
      // version tag, and all other builds publish only "dev".
      val tags        = envTags.getOrElse(if (isReleaseBuild) jibTags.value :+ jibVersion.value else jibTags.value)
      IO.write(versionFile, tags.mkString("\n"))
      versionFile
    },
    libraryDependencies ++= appDependencies ++ testDependencies
  )
