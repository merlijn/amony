package io.amony.lib

import akka.actor.typed.ActorSystem
import better.files.File
import io.amony.App.{logger, mediaLibConfig}
import io.amony.actor.MediaLibActor
import io.amony.actor.MediaLibActor.{Media, Preview, UpsertMedia}
import io.circe.generic.semiauto.deriveCodec
import scribe.Logging

object Migration extends Logging {

  case class MediaOld(
                       id: String,
                       hash: String,
                       uri: String,
                       title: Option[String],
                       duration: Long,
                       thumbnail: ThumbnailOld,
                       resolution: (Int, Int),
                       tags: List[String]
                     )

  case class ThumbnailOld(timestamp: Long)

  def importFromFile(system: ActorSystem[MediaLibActor.Command]) = {

    implicit val thumbnailCodec = deriveCodec[ThumbnailOld]
    implicit val mediaOldCodec = deriveCodec[MediaOld]

    val json = (File(mediaLibConfig.indexPath) / "export.json").contentAsString

    import io.circe.parser.decode

    decode[List[MediaOld]](json).foreach { oldMedia =>

      oldMedia.map { m =>

        val path = (File(mediaLibConfig.libraryPath) / m.uri).path

        val probe = FFMpeg.ffprobe(path)
        val previews = List(
          Preview(m.thumbnail.timestamp, m.thumbnail.timestamp + 3000)
        )

        val media = Media(
          m.id, m.hash, m.uri, m.title, m.duration, probe.fps, m.thumbnail.timestamp, previews, m.resolution, m.tags
        )

        logger.info(s"Imported: ${m.uri}")

        system.tell(UpsertMedia(media))
      }
    }
  }
}
