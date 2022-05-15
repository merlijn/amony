import sbt.Keys.scalaVersion
import sbtassembly.AssemblyPlugin.autoImport.assemblyMergeStrategy

// --- Dependencies

val akkaVersion = "2.6.17"
val akkaHttpVersion = "10.2.7"
val circeVersion = "0.14.1"

val akka                 = "com.typesafe.akka"        %% "akka-actor-typed"           % akkaVersion
val akkaPersistence      = "com.typesafe.akka"        %% "akka-persistence-typed"     % akkaVersion
val akkaStream           = "com.typesafe.akka"        %% "akka-stream"                % akkaVersion
val akkaPersistenceQuery = "com.typesafe.akka"        %% "akka-persistence-query"     % akkaVersion
val akkaHttp             = "com.typesafe.akka"        %% "akka-http"                  % akkaHttpVersion

val circe                = "io.circe"                 %% "circe-core"                 % "0.14.1"
val circeGeneric         = "io.circe"                 %% "circe-generic"              % "0.14.1"
val circeParser          = "io.circe"                 %% "circe-parser"               % "0.14.1"
val akkaHttpCirce        = "de.heikoseeberger"        %% "akka-http-circe"            % "1.36.0"

val jwtCirce             = "com.github.jwt-scala"     %% "jwt-circe"                  % "9.0.2"
val slf4jApi             = "org.slf4j"                 % "slf4j-api"                  % "1.7.30"
val scribeSlf4j          = "com.outr"                 %% "scribe-slf4j"               % "3.5.5"

val betterFiles          = "com.github.pathikrit"     %% "better-files"               % "3.9.1"

val monixReactive        = "io.monix"                 %% "monix-reactive"             % "3.4.0"

val scalaTest            = "org.scalatest"            %% "scalatest"                  % "3.2.9"           % Test
val scalaTestCheck       = "org.scalatestplus"        %% "scalacheck-1-15"            % "3.2.9.0"         % Test

val javaOpts = Nil


// -- Shared options

val excludeLog4j =
  ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl")

val commonSettings = Seq(
  organization := "nl.amony",
  scalaVersion := "2.13.8"
)

def module(name: String) =
  sbt.Project.apply(name, file(name))
    .settings(commonSettings: _*)

// --- Modules

lazy val common =
  module("lib-akka")
    .settings(
      name := "amony-lib-akka",
      libraryDependencies ++= Seq(
        akka,
        akkaPersistence,
        akkaHttp,
        akkaHttpCirce,
      )
    )

lazy val identity =
  module("identity")
    .settings(
      name := "amony-identity",
      libraryDependencies ++= Seq(
        // akka
        akka, akkaPersistence, akkaHttp, jwtCirce, akkaHttpCirce,
        circe, circeGeneric
      )
    )

lazy val media =
  module("media")
    .settings(
      name := "amony-media",
      libraryDependencies ++= Seq(
        akka, akkaPersistence, scribeSlf4j, akkaHttpCirce, circe, circeGeneric, akkaHttp, betterFiles, monixReactive
      )
    )

lazy val solrSearch =
  module("solr-search")
    .settings(
      name := "amony-search-solr",
      libraryDependencies ++= Seq(
        slf4jApi, scribeSlf4j,
        akka,
        "org.apache.solr"           % "solr-core"                  % "8.11.1" excludeAll(excludeLog4j),
        "org.apache.solr"           % "solr-langid"                % "8.11.1" excludeAll(excludeLog4j),
      )
    )

lazy val amony = (project in file("."))
  .dependsOn(identity, media)
  .settings(commonSettings)
  .settings(
    name := "amony-server",
    reStart / javaOptions ++= javaOpts ,
    run / fork             := true,
    run / javaOptions     ++= javaOpts,
    libraryDependencies ++= Seq(

      // logging
      slf4jApi, scribeSlf4j,

      // config loading
      "com.typesafe"              % "config"                     % "1.4.1",
      "com.github.pureconfig"    %% "pureconfig"                 % "0.17.1",
      "com.github.pureconfig"    %% "pureconfig-squants"         % "0.17.1",

      // akka
      akka, akkaStream,

      // akka persistence
      akkaPersistence,
      akkaPersistenceQuery,
      "com.typesafe.akka"        %% "akka-serialization-jackson" % akkaVersion,
      "org.fusesource.leveldbjni" % "leveldbjni-all"             % "1.8",
      "org.iq80.leveldb"          % "leveldb"                    % "0.12",

      // akka http & json serialization
      akkaHttp,
      jwtCirce,
      akkaHttpCirce,
      circe,
      circeGeneric,
      circeParser,

      betterFiles,
      monixReactive,
      "io.methvin"                % "directory-watcher"          % "0.15.0",

      // test
      scalaTest, scalaTestCheck
    ),
    //    assembly / logLevel := Level.Debug,
    assembly / assemblyJarName := "amony.jar",
    assembly / assemblyMergeStrategy := {
      case s if s.endsWith("module-info.class") => MergeStrategy.discard
      case s if s.endsWith("Log4j2Plugins.dat") => MergeStrategy.discard
      case s if s.startsWith("org/iq80/leveldb") => MergeStrategy.first
      case s if s.endsWith("io.netty.versions.properties") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
