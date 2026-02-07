import de.gccc.jib.MappingsHelper
import scala.sys.process._
import sbt.Keys.streams

def isMainBranch: Boolean = {
  val currentBranch = "git rev-parse --abbrev-ref HEAD".!!.trim
  currentBranch == "main"
}

def hasNoLocalChanges: Boolean = "git status --porcelain".!!.isEmpty

//fork in Global := true
cancelable in Global := true

// -- Custom tasks

lazy val buildSolrTarGz = taskKey[Seq[File]]("Creates the solr.tar.gz file")
lazy val jibWriteDockerTagsFile = taskKey[File]("Creates the version.txt file")
lazy val generateSpec = taskKey[File]("Generates the OpenAPI specification for the frontend")

addCommandAlias("format", "; scalafmt; test:scalafmt")

val javaDevOpts = Seq(
  "-DAMONY_SOLR_DELETE_LOCKFILE_ONSTARTUP=true",
  "-DAMONY_SECURE_COOKIES=false",
  "-DAMONY_MEDIA_PATH=../media",
  "-DAMONY_HOME=../media/.amony",
  "-DAMONY_WEB_CLIENT_PATH=../frontend/dist",
  "-DAMONY_AUTH_ENABLED=false",
  "-DAMONY_JWT_SECRET_KEY=development-key"
)

// --- Main project

val circeVersion    = "0.14.15"
val http4sVersion   = "0.23.33"
val tapirVersion    = "1.13.6"
val sttpVersion     = "4.0.15"

lazy val amony = project
  .in(file("."))
  .settings(
    organization := "nl.amony",
    name := "amony-app",
    scalaVersion := "3.7.4",
    scalacOptions := Seq(
      "-rewrite",
      "-source", "future",
      "-encoding", "utf-8"),
    
    Global / cancelable   := true,
    Test / fork := true,
    reStart / javaOptions ++= javaDevOpts,
    run / fork             := true,
    run / javaOptions     ++= javaDevOpts,
    outputStrategy         := Some(StdoutOutput),
    
    Compile / packageBin / mainClass := Some("nl.amony.App"),

    // Jib Docker settings
    jibBaseImage            := "europe-west4-docker.pkg.dev/amony-04c85b/docker-images/amony/base:latest",
    jibRegistry             := "europe-west4-docker.pkg.dev",
    jibName                 := "amony-app",
    jibVersion              := version.value.replace('+', '-'), // + sign is not valid in a docker tag
    jibCustomRepositoryPath := Some("amony-04c85b/docker-images/amony/" + jibName.value),
    jibPlatforms            := Set({if (System.getProperty("os.arch") == "aarch64") JibPlatforms.arm64 else JibPlatforms.amd64}),
    jibImageFormat          := JibImageFormat.OCI,
    jibTags                 := { if (isMainBranch && hasNoLocalChanges) List("latest") else List("dev") },
    jibExtraMappings   ++= {
      // this adds the frontend assets to the docker image
      val webClientDir = (Compile / baseDirectory).value / ".." / "frontend" / "dist"
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

    // Solr tar.gz generation
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
    Compile / resourceGenerators += buildSolrTarGz.taskValue,

    // This is a hack to make to create a file with the same docker tags from the jib build to be able to push them
    jibWriteDockerTagsFile := {
      val versionFile = (Compile / baseDirectory).value / ".docker-tags.txt"
      val tags = jibTags.value :+ jibVersion.value
      IO.write(versionFile, tags.mkString("\n"))
      versionFile
    },

    // Task to generate the OpenAPI spec file
    generateSpec := {
      val log = streams.value.log
      val outputPath = ((Compile / baseDirectory).value / ".." / "frontend" / "openapi.yaml").toPath.normalize()
      log.info(s"Writing open api spec to: $outputPath")
      val classpath = (Compile / fullClasspath).value.map(_.data)
      val urls = classpath.map(_.toURI.toURL).toArray
      val parentLoader = ClassLoader.getPlatformClassLoader
      val loader = new java.net.URLClassLoader(urls, parentLoader)
      val cls = loader.loadClass("nl.amony.GenerateSpec$")
      val module = cls.getField("MODULE$").get(null)
      val method = cls.getMethod("generate", classOf[java.nio.file.Path])
      method.invoke(module, outputPath).asInstanceOf[java.nio.file.Path].toFile
    },

    // All dependencies from all modules combined
    libraryDependencies ++= Seq(

      // general
      "org.sqids"                   %% "sqids"                                       % "0.6.0",
      "com.github.jwt-scala"        %% "jwt-circe"                                   % "11.0.3",
      "org.apache.tika"              % "tika-core"                                   % "3.2.3",
      "org.typelevel"               %% "cats-effect"                                 % "3.6.3",
      "co.fs2"                      %% "fs2-core"                                    % "3.12.2",
      "co.fs2"                      %% "fs2-io"                                      % "3.12.2",
//      "org.apache.directory.studio"  % "org.bouncycastle.bcprov.jdk15"               % "140",

      // config
      "com.github.pureconfig"        %% "pureconfig-core"                            % "0.17.10",
      "com.github.pureconfig"        %% "pureconfig-generic-scala3"                  % "0.17.10",
      "com.typesafe"                  % "config"                                     % "1.4.5",

      // database
      "org.tpolecat"                 %% "skunk-core"                                 % "1.0.0-M12",
      "org.tpolecat"                 %% "skunk-circe"                                % "1.0.0-M12",
      "org.postgresql"                % "postgresql"                                 % "42.7.9",
      "org.liquibase"                 % "liquibase-core"                             % "4.33.0",

      // json
      "io.circe"                     %% "circe-core"                                 % circeVersion,
      "io.circe"                     %% "circe-generic"                              % circeVersion,
      "io.circe"                     %% "circe-parser"                               % circeVersion,

      // observability
      "com.outr"                      %% "scribe"                                    % "3.17.0",
      "com.outr"                      %% "scribe-slf4j"                              % "3.17.0",
      "org.typelevel"                 %% "otel4s-oteljava"                           % "0.14.0",
      "io.opentelemetry"               % "opentelemetry-exporter-otlp"               % "1.58.0" % Runtime,
      "io.opentelemetry"               % "opentelemetry-sdk-extension-autoconfigure" % "1.58.0" % Runtime,
      "org.slf4j"                      % "slf4j-api"                                 % "2.0.17",
      "org.typelevel"                 %% "log4cats-slf4j"                            % "2.7.1",

      // http client
      "com.softwaremill.sttp.client4" %% "core"                                      % sttpVersion,
      "com.softwaremill.sttp.client4" %% "circe"                                     % sttpVersion,
      "com.softwaremill.sttp.client4" %% "cats"                                      % sttpVersion,

      // http server
      "com.softwaremill.sttp.tapir"   %% "tapir-core"                                % tapirVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-http4s-server"                       % tapirVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-cats-effect"                         % tapirVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-swagger-ui-bundle"                   % tapirVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"                          % tapirVersion,
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml"                        % "0.11.10",
      "com.softwaremill.sttp.shared"  %% "fs2"                                       % "1.5.0",
      "org.http4s"                    %% "http4s-ember-server"                       % http4sVersion,
      "org.http4s"                    %% "http4s-dsl"                                % http4sVersion,
      "org.http4s"                    %% "http4s-circe"                              % http4sVersion,
      "org.jsoup"                      % "jsoup"                                     % "1.22.1",

      // solr search
      "org.apache.solr"                % "solr-core"                                 % "8.11.1",
      "org.apache.solr"                % "solr-langid"                               % "8.11.1",

      // Test dependencies
      "org.scalatest"                 %% "scalatest"                                 % "3.2.19"   % Test,
      "org.scalatestplus"             %% "scalacheck-1-15"                           % "3.2.11.0" % Test,
      "com.dimafeng"                  %% "testcontainers-scala-scalatest"            % "0.44.1"   % Test,
      "commons-codec"                  % "commons-codec"                             % "1.21.0"   % Test,
      "org.scalacheck"                %% "scalacheck"                                % "1.19.0"   % Test
    ),

    excludeDependencies ++= List(
      ExclusionRule("org.scala-lang", "scala3-library_sjs"),
      ExclusionRule("org.scala-lang", "scala3-library_sjs1_3"),
      ExclusionRule("org.scala-js", "scalajs-library_2.13"),
      ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl"),
      ExclusionRule("javax.xml.bind", "jaxb-api")
    )
  )
