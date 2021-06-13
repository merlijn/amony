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
      "com.typesafe"      %  "config"                   % "1.4.1",
      "io.circe" %% "circe-core" % "0.14.1",
      "io.circe" %% "circe-generic" % "0.14.1",
      "io.circe" %% "circe-parser" % "0.14.1",
      "org.scalatest"     %% "scalatest"                % "3.2.9"         % Test
    )
  )
