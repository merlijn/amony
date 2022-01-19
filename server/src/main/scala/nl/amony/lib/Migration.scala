package nl.amony.lib

import akka.util.Timeout
import better.files.File
import nl.amony.actor.media.MediaLibProtocol.FileInfo
import nl.amony.actor.media.MediaLibProtocol.Fragment
import nl.amony.actor.media.MediaLibProtocol.Media
import nl.amony.actor.media.MediaLibProtocol.VideoInfo
import io.circe.Error
import io.circe.syntax._
import io.circe.generic.semiauto._
import scribe.Logging

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

object Migration extends Logging {

  def importFromExport(api: AmonyApi)(implicit timeout: Timeout) = {

    implicit val mediaInfoDecoder = deriveDecoder[VideoInfo]
    implicit val fileInfoDecoder  = deriveDecoder[FileInfo]
    implicit val fragmentCodec    = deriveDecoder[Fragment]
    implicit val mediaOldCodec    = deriveDecoder[Media]

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
