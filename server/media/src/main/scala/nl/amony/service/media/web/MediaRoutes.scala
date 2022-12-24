package nl.amony.service.media.web

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directive.addDirectiveApply
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import nl.amony.service.media.web.MediaWebModel._
import nl.amony.service.media.{MediaService, MediaProtocol => protocol}
import nl.amony.service.resources.ResourceConfig.TranscodeSettings
import scribe.Logging

import scala.concurrent.Future

object MediaRoutes extends Logging {

  def apply(
     mediaService: MediaService,
     transcodingSettings: List[TranscodeSettings]
  ): Route = {

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
      }
    }
  }
}
