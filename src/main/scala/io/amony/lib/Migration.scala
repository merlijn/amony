package io.amony.lib

import akka.util.Timeout
import better.files.File
import io.amony.actor.MediaLibActor.{Fragment, Media}
import io.circe.Error
import io.circe.generic.semiauto.deriveCodec
import scribe.Logging

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

object Migration extends Logging {

  case class MediaOld(
    id: String,
    hash: String,
    uri: String,
    title: Option[String],
    duration: Long,
    fps: Double,
    thumbnailTimestamp: Long,
    fragments: List[FragmentOld],
    resolution: (Int, Int),
    tags: List[String]
  )

  case class FragmentOld(fromTimestamp: Long, toTimestamp: Long)

  def importFromExport(api: MediaLibApi)(implicit timeout: Timeout) = {

    implicit val thumbnailCodec = deriveCodec[FragmentOld]
    implicit val mediaOldCodec = deriveCodec[MediaOld]

    val json = (File(api.config.indexPath) / "export.json").contentAsString

    import io.circe.parser.decode

    logger.info("Decoding export.json")

    decode[List[MediaOld]](json) match {

      case Left(error) =>
        logger.error("Failed to decode json", error)

      case Right(media) =>

        media.foreach { m =>
          val fragments = {
            m.fragments.map { p =>
              Fragment(p.fromTimestamp, p.toTimestamp, None, List.empty)
            }
          }

          val path = api.config.libraryPath.resolve(m.uri)
          val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
          val addedOn = attributes.creationTime().toMillis

          logger.info(s"added on: ${path.toString} -> $addedOn")

          val media = Media(
            m.id,
            m.hash,
            m.uri,
            addedOn,
            m.title,
            m.duration,
            m.fps,
            m.thumbnailTimestamp,
            fragments,
            m.resolution,
            m.tags
          )

          logger.info(s"Imported: ${m.uri}")

          api.modify.upsertMedia(media)
        }
    }
  }
}
