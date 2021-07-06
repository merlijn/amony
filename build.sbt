val AkkaVersion = "2.6.14"
val AkkaHttpVersion = "10.2.4"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization    := "com.github.merlijn",
      scalaVersion    := "2.13.6"
    )),
//    fork := false,
    name := "webapp-test",
    libraryDependencies ++= Seq(

      "org.slf4j"             %  "slf4j-api"                % "1.7.30",
      "org.slf4j"             %  "slf4j-simple" % "1.7.30",
      "com.typesafe"          %  "config"                   % "1.4.1",
      "com.typesafe.akka"     %% "akka-actor-typed" % AkkaVersion,
      "com.typesafe.akka"     %% "akka-stream" % AkkaVersion,
      "com.typesafe.akka"     %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka"     %% "akka-persistence-typed" % AkkaVersion,
      "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
      "org.iq80.leveldb"       % "leveldb" % "0.12",
      "de.heikoseeberger"     %% "akka-http-circe" % "1.36.0",
      "com.github.pureconfig" %% "pureconfig" % "0.16.0",
      "io.monix"              %% "monix-reactive" % "3.4.0",
      "io.circe"              %% "circe-core" % "0.14.1",
      "io.circe"              %% "circe-generic" % "0.14.1",
      "io.circe"              %% "circe-parser" % "0.14.1",
      "com.github.pathikrit"  %% "better-files" % "3.9.1",
      "org.scalatest"         %% "scalatest"                % "3.2.9"         % Test
    )
  )
