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
     mediaService: MediaService,
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
        case Right(media)                          => complete(media.asJson)
      }
    }

    pathPrefix("api") {
      pathPrefix("media" / Segment) { id =>
        pathEnd {
          get {
            onSuccess(mediaService.getById(id)) {
              case Some(media) => complete(media.asJson)
              case None        => complete(StatusCodes.NotFound)
            }
          } ~ (post & entity(as[MediaMeta])) { meta =>
            translateResponse(mediaService.updateMetaData(id, meta.title, meta.comment, meta.tags))
          } ~ delete {
            onSuccess(mediaService.deleteMedia(id, deleteResource = true)) { case _ =>
              complete(StatusCodes.OK, "{}")
            }
          }
        } ~ (path("export-all") & get) {
          val json = mediaService.exportToJson()
          complete(StatusCodes.OK, json)
        }
      } ~ path("fragments" / Segment / "add") { (id) =>
        (post & entity(as[Range])) { createFragment =>
          translateResponse(mediaService.addFragment(id, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment) { (id, idx) =>
        delete {
          translateResponse(mediaService.deleteFragment(id, idx.toInt))
        } ~ (post & entity(as[Range])) { createFragment =>
          translateResponse(mediaService.updateFragmentRange(id, idx.toInt, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment / "tags") { (id, idx) =>
        (post & entity(as[List[String]])) { tags =>
          translateResponse(mediaService.updateFragmentTags(id, idx.toInt, tags))
        }
      }
    }
  }
}
