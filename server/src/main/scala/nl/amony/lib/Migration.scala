package nl.amony.lib

import akka.util.Timeout
import better.files.File
import nl.amony.actor.MediaLibProtocol.{FileInfo, Fragment, Media, VideoInfo}
import io.circe.Error
import io.circe.syntax._
import io.circe.generic.semiauto._
import scribe.Logging

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

object Migration extends Logging {

//  case class MediaOld(
//      id: String,
//      hash: String,
//      uri: String,
//      title: Option[String],
//      addedOnTimestamp: Long,
//      duration: Long,
//      fps: Double,
//      thumbnailTimestamp: Long,
//      fragments: List[FragmentOld],
//      resolution: (Int, Int),
//      tags: List[String]
//  )

//  case class FragmentOld(fromTimestamp: Long, toTimestamp: Long)

  def importFromExport(api: AmonyApi)(implicit timeout: Timeout) = {

    implicit val mediaInfoDecoder = deriveDecoder[VideoInfo]
    implicit val fileInfoDecoder = deriveDecoder[FileInfo]
    implicit val fragmentCodec = deriveDecoder[Fragment]
    implicit val mediaOldCodec = deriveDecoder[Media]

    val json = (File(api.config.indexPath) / "export.json").contentAsString

    import io.circe.parser.decode

    logger.info("Importing from export.json")

    decode[List[Media]](json) match {

      case Left(error) =>
        logger.error("Failed to decode json", error)

      case Right(media) =>
        media.foreach { m =>
//          val fragments = {
//            m.fragments.map { p =>
//              Fragment(p.fromTimestamp, p.toTimestamp, None, List.empty)
//            }
//          }
//
//          val path       = api.config.mediaPath.resolve(m.uri)
//          val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
//
//          val fileInfo = FileInfo(
//            m.uri,
//            m.hash,
//            attributes.size(),
//            attributes.creationTime().toMillis,
//            attributes.lastModifiedTime().toMillis
//          )
//
//          val videoInfo = VideoInfo(
//            m.fps,
//            m.duration,
//            m.resolution
//          )
//
//          val media = Media(
//            id                 = m.id,
//            title              = m.title,
//            comment            = None,
//            fileInfo           = fileInfo,
//            videoInfo          = videoInfo,
//            thumbnailTimestamp = m.thumbnailTimestamp,
//            fragments          = fragments,
//            tags               = m.tags.toSet
//          )
//
//          logger.info(s"Imported: ${m.uri}")

          api.modify.upsertMedia(m)
        }
    }
  }
}
