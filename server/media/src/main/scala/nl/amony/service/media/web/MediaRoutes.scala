package nl.amony.service.media.web

import cats.effect.IO
import nl.amony.lib.cats.FutureOps
import nl.amony.service.media.MediaService
import nl.amony.service.media.web.MediaWebModel.MediaMeta
import nl.amony.service.resources.ResourceConfig.TranscodeSettings
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe._
import org.http4s.dsl.io._

object MediaRoutes {

  def apply(mediaService: MediaService,
            transcodingSettings: List[TranscodeSettings]): HttpRoutes[IO] = {

    val jsonCodecs = new JsonCodecs(transcodingSettings)
    import jsonCodecs._

    HttpRoutes.of[IO] {
      case GET        -> Root / "api" / "media" / mediaId =>
        mediaService.getById(mediaId).toIO.flatMap {
          case None        => NotFound()
          case Some(media) => Ok(media)
        }
      case req @ POST -> Root / "api" / "media" / mediaId =>

        mediaService.getById(mediaId).toIO.flatMap {
          case None        => NotFound()
          case Some(media) =>
            req.decodeJson[MediaMeta].flatMap { meta =>
              mediaService.updateMetaData(media.mediaId, meta.title, meta.comment, meta.tags).toIO
            }.flatMap(Ok(_))
        }
      case DELETE -> Root / "api" / "media" / mediaId =>
        Ok(mediaService.deleteMedia(mediaId, true).toIO)
    }
  }
}
