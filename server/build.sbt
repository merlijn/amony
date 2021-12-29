import sbtassembly.AssemblyPlugin.autoImport.assemblyMergeStrategy

val AkkaVersion = "2.6.17"
val AkkaHttpVersion = "10.2.7"
val circeVersion = "0.14.1"

val javaOpts = Nil //Seq("-Dconfig.resource=dev/application.conf", "-Dfile.encoding=UTF-8")

lazy val amony = (project in file(".")).
  settings(
    inThisBuild(List(
      organization    := "nl.amony",
      scalaVersion    := "2.13.6"
    )),
    reStart / javaOptions ++= javaOpts ,
    run / fork             := true,
    run / javaOptions     ++= javaOpts,
    name := "amony-server",
    libraryDependencies ++= Seq(

      "org.slf4j"                 % "slf4j-api"                  % "1.7.30",
      "com.typesafe"              % "config"                     % "1.4.1",
      "com.typesafe.akka"        %% "akka-actor-typed"           % AkkaVersion,
      "com.typesafe.akka"        %% "akka-stream"                % AkkaVersion,
      "com.typesafe.akka"        %% "akka-persistence-typed"     % AkkaVersion,
      "com.typesafe.akka"        %% "akka-persistence-query"     % AkkaVersion,
      "com.typesafe.akka"        %% "akka-serialization-jackson" % AkkaVersion,
      "com.typesafe.akka"        %% "akka-http"                  % AkkaHttpVersion,
      "com.github.jwt-scala"     %% "jwt-circe"                  % "9.0.2",
      "com.outr"                 %% "scribe-slf4j"               % "3.5.5",
      "org.fusesource.leveldbjni" % "leveldbjni-all"             % "1.8",
      "org.iq80.leveldb"          % "leveldb"                    % "0.12",
      "de.heikoseeberger"        %% "akka-http-circe"            % "1.36.0",
      "com.github.pureconfig"    %% "pureconfig"                 % "0.17.1",
      "com.github.pureconfig"    %% "pureconfig-squants"         % "0.17.1",
      "io.monix"                 %% "monix-reactive"             % "3.4.0",
      "io.circe"                 %% "circe-core"                 % "0.14.1",
      "io.circe"                 %% "circe-generic"              % "0.14.1",
      "io.circe"                 %% "circe-parser"               % "0.14.1",
      "com.github.pathikrit"     %% "better-files"               % "3.9.1",
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
