package nl.amony.service.media.tasks

import akka.util.Timeout
import io.circe.Decoder.Result
import io.circe.{Decoder, HCursor}
import io.circe.generic.semiauto.{deriveCodec, deriveDecoder}
import nl.amony.lib.files.PathOps
import nl.amony.service.fragments.FragmentProtocol.Fragment
import nl.amony.service.media.MediaService
import nl.amony.service.media.MediaProtocol._
import nl.amony.service.media.api.protocol.{MediaMeta, ResourceInfo}
import nl.amony.service.media.web.MediaWebModel.MediaInfo
import scalapb.UnknownFieldSet
import scribe.Logging

import java.nio.charset.StandardCharsets
import java.nio.file.Path

object MigrateMedia extends Logging {

  def importFromExport(path: Path, mediaApi: MediaService)(implicit timeout: Timeout) = {

    import io.circe.parser.decode

    implicit val unkownFieldsDecoder = new Decoder[UnknownFieldSet] {
      override def apply(c: HCursor): Result[UnknownFieldSet] = Right(UnknownFieldSet.empty)
    }

    implicit val mediaInfoDecoder = deriveDecoder[MediaInfo]
    implicit val fileInfoDecoder  = deriveDecoder[ResourceInfo]
    implicit val fragmentCodec    = deriveDecoder[Fragment]
    implicit val mediaMetaCodec   = deriveDecoder[MediaMeta]
    implicit val mediaOldCodec    = deriveDecoder[Media]

    val json = path.contentAsString(StandardCharsets.UTF_8)

    logger.info("Importing from export.json")

    decode[List[Media]](json) match {

      case Left(error) =>
        logger.error("Failed to decode json", error)

      case Right(media) =>

        logger.info(s"Found ${media.size} media in export")

        media.foreach { m =>
          logger.info(s"Importing: ${m.resourceInfo.relativePath}")

          mediaApi.upsertMedia(m)
        }
    }
  }
}
