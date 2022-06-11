package nl.amony.webserver.admin

import akka.util.Timeout
import io.circe.generic.semiauto.deriveDecoder
import nl.amony.lib.files.PathOps
import nl.amony.service.media.MediaApi
import nl.amony.service.media.actor.MediaLibProtocol.{FileInfo, Fragment, Media, VideoInfo}
import scribe.Logging

import java.nio.charset.StandardCharsets
import java.nio.file.Path

object MigrateMedia extends Logging {

  def importFromExport(path: Path, mediaApi: MediaApi)(implicit timeout: Timeout) = {

    implicit val mediaInfoDecoder = deriveDecoder[VideoInfo]
    implicit val fileInfoDecoder  = deriveDecoder[FileInfo]
    implicit val fragmentCodec    = deriveDecoder[Fragment]
    implicit val mediaOldCodec    = deriveDecoder[Media]

    val json = path.contentAsString(StandardCharsets.UTF_8)

    import io.circe.parser.decode

    logger.info("Importing from export.json")

    decode[List[Media]](json) match {

      case Left(error) =>
        logger.error("Failed to decode json", error)

      case Right(media) =>

        logger.info(s"Found ${media.size} media in export")

        media.foreach { m =>
          logger.info(s"Importing: ${m.fileInfo.relativePath}")

          mediaApi.upsertMedia(m)
        }
    }
  }
}
