import sbtassembly.AssemblyPlugin.autoImport.assemblyMergeStrategy

val akkaVersion = "2.6.17"
val akkaHttpVersion = "10.2.7"
val circeVersion = "0.14.1"

val javaOpts = Nil

lazy val amony = (project in file(".")).
  settings(
    inThisBuild(List(
      organization    := "nl.amony",
      scalaVersion    := "2.13.8"
    )),
    name := "amony-server",
    reStart / javaOptions ++= javaOpts ,
    run / fork             := true,
    run / javaOptions     ++= javaOpts,
    libraryDependencies ++= Seq(

      // logging
      "org.slf4j"                 % "slf4j-api"                  % "1.7.30",
      "com.outr"                 %% "scribe-slf4j"               % "3.5.5",

      // config loading
      "com.typesafe"              % "config"                     % "1.4.1",
      "com.github.pureconfig"    %% "pureconfig"                 % "0.17.1",
      "com.github.pureconfig"    %% "pureconfig-squants"         % "0.17.1",

      // akka
      "com.typesafe.akka"        %% "akka-actor-typed"           % akkaVersion,
      "com.typesafe.akka"        %% "akka-stream"                % akkaVersion,

      // akka persistence
      "com.typesafe.akka"        %% "akka-persistence-typed"     % akkaVersion,
      "com.typesafe.akka"        %% "akka-persistence-query"     % akkaVersion,
      "com.typesafe.akka"        %% "akka-serialization-jackson" % akkaVersion,
      "org.fusesource.leveldbjni" % "leveldbjni-all"             % "1.8",
      "org.iq80.leveldb"          % "leveldb"                    % "0.12",

      // akka http & json serialization
      "com.typesafe.akka"        %% "akka-http"                  % akkaHttpVersion,
      "com.github.jwt-scala"     %% "jwt-circe"                  % "9.0.2",
      "de.heikoseeberger"        %% "akka-http-circe"            % "1.36.0",
      "io.circe"                 %% "circe-core"                 % "0.14.1",
      "io.circe"                 %% "circe-generic"              % "0.14.1",
      "io.circe"                 %% "circe-parser"               % "0.14.1",

      "io.monix"                 %% "monix-reactive"             % "3.4.0",
      "com.github.pathikrit"     %% "better-files"               % "3.9.1",

      // test
      "org.scalatest"            %% "scalatest"                  % "3.2.9"           % Test,
      "org.scalatestplus"        %% "scalacheck-1-15"            % "3.2.9.0"         % Test
    ),
//    assembly / logLevel := Level.Debug,
    assembly / assemblyJarName := "amony.jar",
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case s if s.startsWith("org/iq80/leveldb") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
      }
  )
