package nl.amony.service.media

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directive.addDirectiveApply
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import nl.amony.service.media.MediaConfig.TranscodeSettings
import nl.amony.service.media.actor.{ MediaLibProtocol => protocol }
import nl.amony.service.media.MediaWebModel._
import scribe.Logging

import scala.concurrent.{ExecutionContext, Future}

object MediaRoutes extends Logging {

  def apply(
             system: ActorSystem[Nothing],
             mediaApi: MediaService,
             transcodingSettings: List[TranscodeSettings]
  ): Route = {

    implicit def materializer: Materializer = Materializer.createMaterializer(system)
    implicit def executionContext: ExecutionContext = system.executionContext

    val jsonCodecs = new JsonCodecs(transcodingSettings)
    import jsonCodecs._

    def translateResponse(future: Future[Either[protocol.ErrorResponse, protocol.Media]]): Route = {
      onSuccess(future) {
        case Left(protocol.MediaNotFound(_))       => complete(StatusCodes.NotFound)
        case Left(protocol.InvalidCommand(reason)) => complete(StatusCodes.BadRequest, reason)
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
          } ~ (post & entity(as[MediaMeta])) { meta =>
            translateResponse(mediaApi.updateMetaData(id, meta.title, meta.comment, meta.tags))
          } ~ delete {
            onSuccess(mediaApi.deleteMedia(id, deleteResource = true)) { case _ =>
              complete(StatusCodes.OK, "{}")
            }
          }
        }
      } ~ path("fragments" / Segment / "add") { (id) =>
        (post & entity(as[Range])) { createFragment =>
          translateResponse(mediaApi.addFragment(id, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment) { (id, idx) =>
        delete {
          translateResponse(mediaApi.deleteFragment(id, idx.toInt))
        } ~ (post & entity(as[Range])) { createFragment =>
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
