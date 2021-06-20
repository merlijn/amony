lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization    := "com.github.merlijn",
      scalaVersion    := "3.0.0"
    )),
    name := "webapp-test",
    libraryDependencies ++= Seq(

      "org.http4s"        %% "http4s-dsl"               % "1.0.0-M23",
      "org.http4s"        %% "http4s-blaze-server"      % "1.0.0-M23",
      "org.slf4j" % "slf4j-api" % "1.7.30",
      "org.slf4j" % "slf4j-simple" % "1.7.30",
      "com.typesafe"      %  "config"                   % "1.4.1",
      "io.circe" %% "circe-core" % "0.14.1",
      "io.circe" %% "circe-generic" % "0.14.1",
      "io.circe" %% "circe-parser" % "0.14.1",
      ("com.github.pathikrit" %% "better-files" % "3.9.1").cross(CrossVersion.for3Use2_13),
      "org.scalatest"     %% "scalatest"                % "3.2.9"         % Test
    )
  )
