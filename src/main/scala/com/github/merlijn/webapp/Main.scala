package com.github.merlijn.webapp

object Model {

  import io.circe.*
  import io.circe.generic.auto.*
  import io.circe.parser.*
  import io.circe.syntax.*

  case class Movie(title: String, thumbnail: String, id: String)

  val foo = Movie("foo", "bar", "1")

  val json = foo.asJson.noSpaces
  println(json)
}

object API {

  import better.files.*
  import File.*
  import java.io.{File as JFile}
  import Model.*

  def index(): Unit = {
    val dir = "/Users/merlijn" / "Downloads"
    val matches: Iterator[File] = dir.listRecursively.filter(_.name.endsWith(".mp4")) //.filter(f => f.extension == Some(".java") || f.extension == Some(".scala")) //dir.glob("**/*.{mp4,wmv}")

    matches.foreach { f =>

      val fileName = s"$dir/${f.name}"
      ffprobe(fileName)
      writeThumbnail(fileName, "00:05:12", "/Users/merlijn/Downloads")
    }
  }

  def ffprobe(fileName: String): Unit = {

    val command = s"ffprobe $fileName"

    val output = run(command)
    val pattern = "Duration:\\s(\\d\\d:\\d\\d:\\d\\d)".r
    val duration = pattern.findFirstIn(output)

    println(s"duration: $duration")
  }

  def writeThumbnail(fileName: String, time: String, dir: String): Unit = {

    val command = s"ffmpeg -ss $time -i $fileName -vframes 1 $dir/test.jpeg"
    val output = run(command)

    println(output)
  }

  def run(command: String): String = {

    import java.io.BufferedReader
    import java.io.InputStreamReader

    // A Runtime object has methods for dealing with the OS
    val r = Runtime.getRuntime
    val p = r.exec(command)
    val is = p.getErrorStream
    val output = scala.io.Source.fromInputStream(is).mkString
    val exitCode = p.waitFor()
    println(s"exit code: $exitCode")

    output
  }
}

trait WebServer {

  import scala.concurrent.ExecutionContext.global
  import com.typesafe.config.Config
  import com.typesafe.config.ConfigFactory

  import cats.effect.*
  import cats.syntax.all.*
  import org.http4s
  import org.http4s.HttpRoutes
  import org.http4s.dsl.io.*
  import org.http4s.implicits.*
  import org.http4s.blaze.server.BlazeServerBuilder
  import org.http4s.server.Router

  object config {
    val conf = ConfigFactory.load

    val port = conf.getInt("webapp.http.port")
    val hostname = conf.getString("webapp.http.hostName")
  }

  val helloWorldService = HttpRoutes.of[IO] {
    case GET -> Root / "movie" / name => Ok(s"""{ "title" : "Hello World!"}""")
  }.orNotFound

  BlazeServerBuilder[IO](global)
    .bindHttp(config.port, config.hostname)
    .withHttpApp(helloWorldService)
    .serve
    .compile
    .drain
    .unsafeRunSync()(cats.effect.unsafe.implicits.global)
}

object Main extends App {

  API.index()

}
