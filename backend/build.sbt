import de.gccc.jib.MappingsHelper
import scala.sys.process._
import sbt.Keys.streams

def isMainBranch: Boolean = {
  val currentBranch = "git rev-parse --abbrev-ref HEAD".!!.trim
  currentBranch == "main"
}

def hasNoLocalChanges: Boolean = "git status --porcelain".!!.isEmpty

// --- Dependencies

val circeVersion    = "0.14.15"
val http4sVersion   = "0.23.33"
val tapirVersion    = "1.13.6"
val sttpVersion     = "4.0.15"

val bouncyCastle = "org.apache.directory.studio" % "org.bouncycastle.bcprov.jdk15" % "140"

val jsoup  = "org.jsoup" % "jsoup" % "1.22.1"

val circe                    = "io.circe"                 %% "circe-core"                 % circeVersion
val circeGeneric             = "io.circe"                 %% "circe-generic"              % circeVersion
val circeParser              = "io.circe"                 %% "circe-parser"               % circeVersion

val skunkCore                = "org.tpolecat"             %% "skunk-core"                 % "1.0.0-M12"
val skunkCirce               = "org.tpolecat"             %% "skunk-circe"                % "1.0.0-M12"

val sqids                    = "org.sqids" %% "sqids" % "0.6.0"

val tapirCore                = "com.softwaremill.sttp.tapir"   %% "tapir-core"              % tapirVersion
val tapirHttp4s              = "com.softwaremill.sttp.tapir"   %% "tapir-http4s-server"     % tapirVersion
val tapirCatsEffect          = "com.softwaremill.sttp.tapir"   %% "tapir-cats-effect"       % tapirVersion
val tapirSwaggerUI           = "com.softwaremill.sttp.tapir"   %% "tapir-swagger-ui-bundle" % tapirVersion
val tapirCirce               = "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"        % tapirVersion
val tapirCirceYamlSpec       = "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml"      % "0.11.10"

val tapirSharedFs2           = "com.softwaremill.sttp.shared"  %% "fs2"                   % "1.5.0"

val jwtCirce                 = "com.github.jwt-scala"     %% "jwt-circe"                  % "11.0.3"
val slf4jApi                 = "org.slf4j"                 % "slf4j-api"                  % "2.0.17"

val tikaCore                 = "org.apache.tika"           % "tika-core"                  % "3.2.3"

val fs2Core                  = "co.fs2"                   %% "fs2-core"                   % "3.12.2"
val fs2Io                    = "co.fs2"                   %% "fs2-io"                     % "3.12.2"
val catsEffect               = "org.typelevel"            %% "cats-effect"                % "3.6.3"

val scalaTest                = "org.scalatest"            %% "scalatest"                  % "3.2.19"           % Test
val scalaTestCheck           = "org.scalatestplus"        %% "scalacheck-1-15"            % "3.2.11.0"         % Test

val hsqlDB                   = "org.hsqldb"                % "hsqldb"                     % "2.7.4"
val postgresDriver           = "org.postgresql"            % "postgresql"                 % "42.7.9"

val pureConfig               = "com.github.pureconfig"    %% "pureconfig-core"            % "0.17.10"
val pureConfigGeneric        = "com.github.pureconfig"    %% "pureconfig-generic-scala3"  % "0.17.10"
val typesafeConfig           = "com.typesafe"              % "config"                     % "1.4.5"

val liquibaseCore            = "org.liquibase"             % "liquibase-core"             % "4.33.0"

val solr                     = "org.apache.solr"           % "solr-core"                  % "8.11.1"
val solrLangId               = "org.apache.solr"           % "solr-langid"                % "8.11.1"

val log4CatsSlf4j            = "org.typelevel"            %% "log4cats-slf4j"             % "2.7.1"

val sttpClientCore           = "com.softwaremill.sttp.client4" %% "core"                  % sttpVersion
val sttpClientCirce          = "com.softwaremill.sttp.client4" %% "circe"                  % sttpVersion
val sttpClientCats           = "com.softwaremill.sttp.client4" %% "cats"                  % sttpVersion

val apacheCommonsCodec = "commons-codec" % "commons-codec" % "1.21.0"


val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % http4sVersion
val http4sEmberClient = "org.http4s" %% "http4s-ember-client" % http4sVersion
val http4sDsl         = "org.http4s" %% "http4s-dsl"          % http4sVersion
val http4sCirce       = "org.http4s" %% "http4s-circe"        % http4sVersion


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
      // From lib
      circe, circeGeneric,
      circeParser,
      fs2Core,
      fs2Io,
      pureConfig,
      pureConfigGeneric,
      "com.outr"                 %% "scribe"               % "3.17.0",
      "com.outr"                 %% "scribe-slf4j"         % "3.17.0",
      "org.typelevel" %% "otel4s-oteljava" % "0.14.0",

      tapirCore, tapirCirce, tapirCatsEffect, tapirHttp4s, tapirSharedFs2, tapirSwaggerUI, tapirCirceYamlSpec,
      
      // From auth
      jwtCirce, bouncyCastle,
      
      // From resources
      sqids, jsoup, tikaCore,
      http4sDsl, http4sCirce,
      liquibaseCore,
      skunkCore, skunkCirce,
      
      // From search service
      slf4jApi,
      solr, solrLangId,
      catsEffect,
      
      // From app
      log4CatsSlf4j,
      typesafeConfig,
      postgresDriver,
      http4sEmberServer,
      
      // STTP clients
      sttpClientCore, sttpClientCirce, sttpClientCats,
      
      // Test dependencies
      scalaTest,
      scalaTestCheck,
      postgresDriver % "test",
      "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.44.1" % "test",
      "commons-codec" % "commons-codec" % "1.21.0" % "test",
      "org.scalacheck" %% "scalacheck" % "1.19.0" % "test"
    ),

    excludeDependencies ++= List(
      ExclusionRule("org.scala-lang", "scala3-library_sjs"),
      ExclusionRule("org.scala-lang", "scala3-library_sjs1_3"),
      ExclusionRule("org.scala-js", "scalajs-library_2.13"),
      ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl"),
      ExclusionRule("javax.xml.bind", "jaxb-api")
    )
  )
