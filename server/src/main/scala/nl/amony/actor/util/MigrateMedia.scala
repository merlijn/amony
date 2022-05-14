package nl.amony.actor.util

import akka.util.Timeout
import better.files.File
import io.circe.generic.semiauto.deriveDecoder
import nl.amony.actor.media.MediaLibProtocol.{FileInfo, Fragment, Media, VideoInfo}
import nl.amony.api.MediaApi
import scribe.Logging

import java.nio.file.Path

object MigrateMedia extends Logging {

  def importFromExport(path: Path, mediaApi: MediaApi)(implicit timeout: Timeout) = {

    implicit val mediaInfoDecoder = deriveDecoder[VideoInfo]
    implicit val fileInfoDecoder = deriveDecoder[FileInfo]
    implicit val fragmentCodec = deriveDecoder[Fragment]
    implicit val mediaOldCodec = deriveDecoder[Media]

    val json = File(path).contentAsString

    import io.circe.parser.decode

    logger.info("Importing from export.json")

    decode[List[Media]](json) match {

      case Left(error) =>
        logger.error("Failed to decode json", error)

      case Right(media) =>
        media.foreach { m =>

          logger.info(s"Importing: ${m.fileInfo.relativePath}")

          mediaApi.upsertMedia(m)
        }
    }
  }
}
