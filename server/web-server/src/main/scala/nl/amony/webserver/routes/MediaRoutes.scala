package nl.amony.webserver.routes

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directive.addDirectiveApply
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import nl.amony.webserver.WebServerConfig
import nl.amony.search.SearchProtocol._
import nl.amony.service.media.MediaConfig.TranscodeSettings
import nl.amony.service.media.actor.MediaLibProtocol._
import nl.amony.webserver.{JsonCodecs, WebServerConfig}
import nl.amony.webserver.WebModel.FragmentRange
import nl.amony.webserver.WebModel.VideoMeta
import nl.amony.service.media.MediaApi
import scribe.Logging

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object MediaRoutes extends Logging {

  def apply(
      system: ActorSystem[Nothing],
      mediaApi: MediaApi,
      transcodingSettings: List[TranscodeSettings],
      config: WebServerConfig
  ): Route = {

    implicit def materializer: Materializer = Materializer.createMaterializer(system)
    implicit def executionContext: ExecutionContext = system.executionContext

    val jsonCodecs = new JsonCodecs(transcodingSettings)
    import jsonCodecs._

    def translateResponse(future: Future[Either[ErrorResponse, Media]]): Route = {
      onSuccess(future) {
        case Left(MediaNotFound(_))       => complete(StatusCodes.NotFound)
        case Left(InvalidCommand(reason)) => complete(StatusCodes.BadRequest, reason)
        case Right(media)                 => complete(media.asJson)
      }
    }

    pathPrefix("api") {
      pathPrefix("media" / Segment) { id =>
        pathEnd {
          get {
            onSuccess(mediaApi.getById(id)) {
              case Some(media) => complete(media.asJson)
              case None        => complete(StatusCodes.NotFound)
            }
          } ~ (post & entity(as[VideoMeta])) { meta =>
            translateResponse(mediaApi.updateMetaData(id, meta.title, meta.comment, meta.tags))
          } ~ delete {
            onSuccess(mediaApi.deleteMedia(id, deleteResource = true)) { case _ =>
              complete(StatusCodes.OK, "{}")
            }
          }
        }
      } ~ path("fragments" / Segment / "add") { (id) =>
        (post & entity(as[FragmentRange])) { createFragment =>
          translateResponse(mediaApi.addFragment(id, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment) { (id, idx) =>
        delete {
          translateResponse(mediaApi.deleteFragment(id, idx.toInt))
        } ~ (post & entity(as[FragmentRange])) { createFragment =>
          translateResponse(mediaApi.updateFragmentRange(id, idx.toInt, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment / "tags") { (id, idx) =>
        (post & entity(as[List[String]])) { tags =>
          translateResponse(mediaApi.updateFragmentTags(id, idx.toInt, tags))
        }
      }
    }
  }
}
