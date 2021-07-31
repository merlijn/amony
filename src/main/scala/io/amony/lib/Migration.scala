package io.amony.lib

import akka.actor.typed.ActorSystem
import better.files.File
import io.amony.App.{logger, mediaLibConfig}
import io.amony.actor.MediaLibActor
import io.amony.actor.MediaLibActor.{Media, Fragment, UpsertMedia}
import io.circe.generic.semiauto.deriveCodec
import scribe.Logging

object Migration extends Logging {

  case class MediaOld(
                       id: String,
                       hash: String,
                       uri: String,
                       title: Option[String],
                       duration: Long,
                       fps: Double,
                       thumbnailTimestamp: Long,
                       previews: List[ThumbnailOld],
                       resolution: (Int, Int),
                       tags: List[String])

  case class ThumbnailOld(timestampStart: Long, timestampEnd: Long)

  def importFromFile(system: ActorSystem[MediaLibActor.Command]) = {

    implicit val thumbnailCodec = deriveCodec[ThumbnailOld]
    implicit val mediaOldCodec = deriveCodec[MediaOld]

    val json = (File(mediaLibConfig.indexPath) / "export.json").contentAsString

    import io.circe.parser.decode

    decode[List[MediaOld]](json).foreach { oldMedia =>

      oldMedia.map { m =>

        val fragments = {
          m.previews.map { p =>
            Fragment(p.timestampStart, p.timestampEnd, None, List.empty)
          }
        }

        val media = Media(
          m.id, m.hash, m.uri, m.title, m.duration, m.fps, m.thumbnailTimestamp, fragments, m.resolution, m.tags
        )

        logger.info(s"Imported: ${m.uri}")

        system.tell(UpsertMedia(media))
      }
    }
  }
}
