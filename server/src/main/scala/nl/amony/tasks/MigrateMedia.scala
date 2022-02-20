package nl.amony.tasks

import akka.util.Timeout
import better.files.File
import io.circe.generic.semiauto.deriveDecoder
import nl.amony.AmonyApi
import nl.amony.actor.media.MediaLibProtocol.{FileInfo, Fragment, Media, VideoInfo}
import scribe.Logging

object MigrateMedia extends Logging {

  def importFromExport(api: AmonyApi)(implicit timeout: Timeout) = {

    implicit val mediaInfoDecoder = deriveDecoder[VideoInfo]
    implicit val fileInfoDecoder = deriveDecoder[FileInfo]
    implicit val fragmentCodec = deriveDecoder[Fragment]
    implicit val mediaOldCodec = deriveDecoder[Media]

    val json = (File(api.config.media.indexPath) / "export.json").contentAsString

    import io.circe.parser.decode

    logger.info("Importing from export.json")

    decode[List[Media]](json) match {

      case Left(error) =>
        logger.error("Failed to decode json", error)

      case Right(media) =>
        media.foreach { m =>

          logger.info(s"Importing: ${m.fileInfo.relativePath}")

          api.modify.upsertMedia(m)
        }
    }
  }
}
