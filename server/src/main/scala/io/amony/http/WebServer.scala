package io.amony.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{path, _}
import akka.http.scaladsl.server.{RejectionHandler, Route, ValidationRejection}
import akka.stream.Materializer
import akka.util.Timeout
import better.files.File
import io.amony.http.WebConversions._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.amony.actor.MediaLibActor.{ErrorResponse, InvalidCommand, Media, MediaNotFound}
import io.amony.http.WebModel.FragmentRange
import io.amony.lib.MediaLibApi
import io.circe.syntax._
import scribe.Logging

import java.security.SecureRandom
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}


case class WebServerConfig(
  hostName: String,
  webClientFiles: String,
  requestTimeout: FiniteDuration,
  http: Option[HttpConfig],
  https: Option[HttpsConfig]
)

case class HttpsConfig(
  port: Int,
  privateKeyPem: String,
  certificateChainPem: String
)

case class HttpConfig(
  port: Int
)

class WebServer(val config: WebServerConfig, val api: MediaLibApi)(implicit val system: ActorSystem[Nothing])
    extends Logging
    with JsonCodecs {

  implicit def materializer: Materializer = Materializer.createMaterializer(system)
  implicit def executionContext: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = Timeout.durationToTimeout(config.requestTimeout)

  val defaultResultNumber = 24

  val rejectionHandler = RejectionHandler
    .newBuilder()
    .handleNotFound { complete(StatusCodes.NotFound, """{"statusCode" : 404 }""") }
    .handle { case ValidationRejection(msg, _) => complete(StatusCodes.InternalServerError, msg) }
    .result()

  val apiRoutes =
    pathPrefix("api") {
      (path("search") & parameters(
        "q".optional,
        "offset".optional,
        "n".optional,
        "tags".optional,
        "sort".optional,
        "min_res".optional
      )) { (q, offset, n, tags, sort, minResY) =>
        get {
          val size         = n.map(_.toInt).getOrElse(defaultResultNumber)
          val searchResult = api.read.search(q, offset.map(_.toInt), size, tags, minResY.map(_.toInt))
          val response     = searchResult.map(_.toWebModel().asJson)

          complete(response)
        }
      } ~ path("media" / Segment) { id =>
        get {
          onSuccess(api.read.getById(id)) {
            case Some(vid) => complete(vid.toWebModel.asJson)
            case None      => complete(StatusCodes.NotFound)
          }
        } ~ delete {
          onSuccess(api.modify.deleteMedia(id)) { case _ =>
            complete(StatusCodes.OK, "{}")
          }
        }
      } ~ path("tags") {
        get {
          complete(api.read.getTags().map(_.map(_.toWebModel.asJson)))
        }
      } ~ path("fragments" / Segment / "add") { (id) =>
        (post & entity(as[FragmentRange])) { createFragment =>
          translateResponse(api.modify.addFragment(id, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment) { (id, idx) =>
        delete {
          translateResponse(api.modify.deleteFragment(id, idx.toInt))
        } ~ (post & entity(as[FragmentRange])) { createFragment =>
          translateResponse(api.modify.updateFragmentRange(id, idx.toInt, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment / "tags") { (id, idx) =>
        (post & entity(as[List[String]])) { tags =>
          translateResponse(api.modify.updateFragmentTags(id, idx.toInt, tags))
        }
      }
    }

  def translateResponse(future: Future[Either[ErrorResponse, Media]]): Route = {
    onSuccess(future) {
      case Left(MediaNotFound(id))      => complete(StatusCodes.NotFound)
      case Left(InvalidCommand(reason)) => complete(StatusCodes.BadRequest, reason)
      case Right(media)                 => complete(media.toWebModel().asJson)
    }
  }

  val adminRoutes = pathPrefix("api" / "admin") {
    (path("regen-previews") & post) {
      api.admin.regeneratePreviews()
      complete("OK")
    } ~ (path("export-to-file") & post) {
      api.admin.exportLibrary()
      complete("OK")
    } ~ (path("verify-hashes") & post) {
      api.admin.verifyHashes()
      complete("OK")
    } ~ (path("convert-non-streamable-videos") & post) {
      api.admin.convertNonStreamableVideos()
      complete("OK")
    } ~ (path("scan-library") & post) {
      api.admin.scanLibrary()
      complete("OK")
    }
  }

  val thumbnailRoutes =
    path("files" / "thumbnails" / Segment) { id =>
      getFromFile(api.read.getThumbnailPathForMedia(id))
    }

  val videoRoutes = path("files" / "videos" / Segment) { id =>
    onSuccess(api.read.getById(id)) {
      case None       => complete(StatusCodes.NotFound, "")
      case Some(info) => getFromFile(api.read.getFilePathForMedia(info))
    }
  }

  def clientFiles: Route =
    rawPathPrefix(Slash) {

      extractUnmatchedPath { path =>
        // TODO sanitize
        val filePath = path.toString() match {
          case "" | "/" => "index.html"
          case other    => other
        }

        val targetFile = {
          val maybe = (File(config.webClientFiles) / filePath)
          if (maybe.exists)
            maybe
          else
            File(config.webClientFiles) / "index.html"
        }

        getFromFile(targetFile.path.toAbsolutePath.toString)
      }
    }

  val allRoutes = apiRoutes ~ adminRoutes ~ thumbnailRoutes ~ videoRoutes ~ clientFiles


  def run(): Unit = {

    def addBindingHooks(protocol: String, f: Future[Http.ServerBinding]) =
      f.onComplete {
        case Success(binding) =>
          val address = binding.localAddress
          system.log.info("Server online at {}://{}:{}/", protocol, address.getHostString, address.getPort)
        case Failure(ex) =>
          system.log.error("Failed to bind to endpoint, terminating system", ex)
          system.terminate()
      }

    config.https.foreach { httpsConfig =>

      logger.info("Loading SSL files")

      val keyStore = PemReader.loadKeyStore(
        certificateChainFile = File(httpsConfig.certificateChainPem),
        privateKeyFile = File(httpsConfig.privateKeyPem),
        keyPassword = None
      )

      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore, "".toCharArray)
      val managers = keyManagerFactory.getKeyManagers

      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(managers, null, new SecureRandom())

      val httpsConnectionContext = ConnectionContext.httpsServer(sslContext)

      val binding = Http().newServerAt(config.hostName, httpsConfig.port).enableHttps(httpsConnectionContext).bind(allRoutes)

      addBindingHooks("https", binding)
    }

    config.http.foreach { httpConfig =>
      val bindingFuture = Http().newServerAt(config.hostName, httpConfig.port).bind(allRoutes)
      addBindingHooks("http", bindingFuture)
    }
  }
}
