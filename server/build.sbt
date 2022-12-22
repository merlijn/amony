import sbt.Keys.scalaVersion
import sbtassembly.AssemblyPlugin.autoImport.assemblyMergeStrategy

// --- Dependencies


val excludeLog4j =
  ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl")

val akkaVersion     = "2.6.19"
val akkaHttpVersion = "10.2.9"
val circeVersion    = "0.14.2"

val akka                     = "com.typesafe.akka"        %% "akka-actor-typed"           % akkaVersion
val akkaPersistence          = "com.typesafe.akka"        %% "akka-persistence-typed"     % akkaVersion
val akkaPersistenceJdbc      = "com.lightbend.akka"       %% "akka-persistence-jdbc"      % "5.0.4"         // no scala 3
val akkaStream               = "com.typesafe.akka"        %% "akka-stream"                % akkaVersion
val akkaPersistenceQuery     = "com.typesafe.akka"        %% "akka-persistence-query"     % akkaVersion
val akkaSerializationJackson = "com.typesafe.akka"        %% "akka-serialization-jackson" % akkaVersion

val akkaHttp                 = "com.typesafe.akka"        %% "akka-http"                  % akkaHttpVersion // no scala 3
val akkaHttpCirce            = "de.heikoseeberger"        %% "akka-http-circe"            % "1.39.2"        // no scala 3

val circe                    = "io.circe"                 %% "circe-core"                 % circeVersion
val circeGeneric             = "io.circe"                 %% "circe-generic"              % circeVersion
val circeParser              = "io.circe"                 %% "circe-parser"               % circeVersion

val slick                    = "com.typesafe.slick"       %% "slick"                      % "3.4.1"
val slickHikariCp            = "com.typesafe.slick"       %% "slick-hikaricp"             % "3.4.1"
val scalaLikeJdbc            = "org.scalikejdbc"          %% "scalikejdbc"                % "4.0.0"

val jwtCirce                 = "com.github.jwt-scala"     %% "jwt-circe"                  % "9.0.5"
val slf4jApi                 = "org.slf4j"                 % "slf4j-api"                  % "1.7.30"
val scribeSlf4j              = "com.outr"                 %% "scribe-slf4j"               % "3.5.5"

val monixReactive            = "io.monix"                 %% "monix-reactive"             % "3.4.1"

val fs2Core = "co.fs2" %% "fs2-core" % "3.3.0"

val scalaTest                = "org.scalatest"            %% "scalatest"                  % "3.2.14"           % Test
val scalaTestCheck           = "org.scalatestplus"        %% "scalacheck-1-15"            % "3.2.11.0"         % Test

val hsqlDB                   = "org.hsqldb"                % "hsqldb"                     % "2.6.1"
val h2DB                     = "com.h2database"            % "h2"                         % "2.1.214"
val flywayDbCore             = "org.flywaydb"              % "flyway-core"                % "8.5.12"
val caffeine                 = "com.github.ben-manes.caffeine" % "caffeine"               % "3.1.1"

val pureConfig               = "com.github.pureconfig"    %% "pureconfig"                 % "0.17.1" // no scala 3
val pureConfigSquants        = "com.github.pureconfig"    %% "pureconfig-squants"         % "0.17.1" // no scala 3
val typesafeConfig           = "com.typesafe"              % "config"                     % "1.4.2"

//val betterFiles              = "com.github.pathikrit"     %% "better-files"               % "3.9.1"
val directoryWatcher         = "io.methvin"                % "directory-watcher"          % "0.15.0"
val solr                     = "org.apache.solr"           % "solr-core"                  % "8.11.1" excludeAll(excludeLog4j)
val solrLangId               = "org.apache.solr"           % "solr-langid"                % "8.11.1" excludeAll(excludeLog4j)

val levelDb                  = "org.iq80.leveldb"          % "leveldb"                    % "0.12"
val levelDbJndiAll           = "org.fusesource.leveldbjni" % "leveldbjni-all"             % "1.8"

val scalaPbRuntimeGrcp       = "com.thesamet.scalapb"     %% "scalapb-runtime-grpc"       % scalapb.compiler.Version.scalapbVersion
val scalaPbRuntimeProtobuf   = "com.thesamet.scalapb"     %% "scalapb-runtime"            % scalapb.compiler.Version.scalapbVersion % "protobuf"


val javaOpts = Nil


// -- Shared options


val commonSettings = Seq(
  organization := "nl.amony",
  scalaVersion := "2.13.8"
)

lazy val noPublishSettings = Seq(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false
)

lazy val protobufSettings = Seq(
  Compile / PB.targets := Seq(
    scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
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

lazy val common =
  module("common")
    .settings(
      name := "amony-lib-common",
      libraryDependencies ++= Seq(
        akka,akkaStream,
        akkaPersistence,
        monixReactive,
        pureConfig,
        scribeSlf4j,
        scalaTest,
        directoryWatcher,
        slick,
//        scalaLikeJdbc,
        circe,
        circeGeneric,
        circeParser,
      )
    )

lazy val libFFMPeg =
  module("lib-ffmpeg")
    .dependsOn(common)
    .settings(
      name         := "amony-lib-ffmpeg",
      libraryDependencies ++= Seq(
//        pureConfig,
        scribeSlf4j,
        monixReactive,
//        fs2Core,
//        slick,
        scalaTest,
      )
    )

lazy val libEventStore =
  module("lib-eventstore")
    .settings(
      name         := "amony-lib-eventstore",
      libraryDependencies ++= Seq(
        pureConfig,
        scribeSlf4j,
//        monixReactive,
        fs2Core,
        slick,
        scalaTest,
        slickHikariCp % "test",
        h2DB % "test"
      )
    )

lazy val identity =
  module("identity")
    .dependsOn(common)
    .settings(protobufSettings)
    .settings(
      name := "amony-service-auth",
      libraryDependencies ++= Seq(
        // akka
        akka, akkaPersistence, akkaHttp, jwtCirce, akkaHttpCirce,
        circe, circeGeneric, pureConfig, slick,
        scalaPbRuntimeGrcp, scalaPbRuntimeProtobuf
      )
    )

lazy val resources =
  module("resources")
    .dependsOn(common, libFFMPeg)
    .settings(protobufSettings)
    .settings(
      name := "amony-service-resources",
      libraryDependencies ++= Seq(
        scribeSlf4j, akka, akkaPersistence, akkaSerializationJackson,
        akkaHttp, akkaHttpCirce, circe, circeGeneric, monixReactive,
        scalaTest,
        slick,
        scalaPbRuntimeGrcp
      )
    )

lazy val media =
  module("media")
    .dependsOn(common, resources)
    .settings(protobufSettings)
    .settings(
      name := "amony-service-media",
      libraryDependencies ++= Seq(
        scribeSlf4j,
        akka, akkaPersistence, akkaSerializationJackson,
        scalaPbRuntimeGrcp, scalaPbRuntimeProtobuf,
        akkaHttp, akkaHttpCirce, circe, circeGeneric,
        monixReactive,
        scalaTest,
        slick, h2DB
      )
    )

lazy val searchApi =
  module("search-api")
    .dependsOn(common, media)
    .settings(
      name := "amony-service-search-api",
      libraryDependencies ++= Seq(
        // akka
        akka, akkaPersistence, akkaPersistenceQuery, akkaHttp, akkaHttpCirce, circe, circeGeneric
      )
    )

lazy val solrSearch =
  module("solr-search")
    .dependsOn(common, searchApi)
    .settings(
      name := "amony-service-search-solr",
      libraryDependencies ++= Seq(
        slf4jApi, scribeSlf4j,
        akka, solr, solrLangId
      )
    )

lazy val amonyServer =
  module("web-server", mainClass = true)
    .dependsOn(identity, media, searchApi)
    .settings(
      name := "amony-web-server",
      reStart / javaOptions ++= javaOpts,
      run / fork             := true,
      run / javaOptions     ++= javaOpts,
      libraryDependencies ++= Seq(

        // logging
        slf4jApi, scribeSlf4j,

        // config loading
        typesafeConfig, pureConfig, pureConfigSquants,

        // akka
        akka, akkaStream,

        // akka persistence
        akkaPersistence,
        akkaPersistenceQuery,
        akkaSerializationJackson,
        levelDb, levelDbJndiAll, flywayDbCore,
        slickHikariCp, hsqlDB,
        h2DB,
//        akkaPersistenceJdbc,

        // akka http & json serialization
        akkaHttp, akkaHttpCirce,
        circe,
        circeGeneric,
        circeParser,

        monixReactive,

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
  .aggregate(common, libEventStore, libFFMPeg, identity, media, searchApi, solrSearch, amonyServer)