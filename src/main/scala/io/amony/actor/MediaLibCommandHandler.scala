package io.amony.actor

import akka.persistence.typed.scaladsl.Effect
import better.files.File
import better.files.File.apply
import io.amony.lib.MediaLibScanner.{deleteThumbnailAtTimestamp, generateThumbnail}
import io.amony.actor.MediaLibEventSourcing._
import io.amony.actor.MediaLibActor._
import io.amony.lib.MediaLibConfig

object MediaLibCommandHandler {

  def apply(config: MediaLibConfig)(state: State, cmd: Command): Effect[Event, State] = {

    lazy val collections: List[Collection] = {

      val dirs = state.media.values.foldLeft(Set.empty[String]) { case (set, e) =>
        val parent   = (File(config.libraryPath) / e.uri).parent
        val relative = s"/${config.libraryPath.relativize(parent)}"
        set + relative
      }

      dirs.toList.sorted.zipWithIndex.map { case (e, idx) =>
        Collection(idx.toString, e)
      }
    }

    lazy val orderedMedia = state.media.values.toList.sortBy(m => m.title.getOrElse(m.fileName()))

    cmd match {

      case UpsertMedia(media) =>
        Effect.persist(MediaAdded(media))

      case RemoveMedia(id) =>
        Effect.persist(MediaRemoved(id))

      case GetById(id, sender) =>
        Effect.reply(sender)(state.media.get(id))

      case GetCollections(sender) =>
        Effect.reply(sender)(collections.sortBy(_.title))

      case GetAll(sender) =>
        Effect.reply(sender)(state.media.values.toList)

      case Search(query, sender) =>
        val col = query.c match {
          case None => orderedMedia
          case Some(id) =>
            collections
              .find(_.id == id)
              .map { cid =>
                orderedMedia.filter(_.uri.startsWith(cid.title.substring(1)))
              }
              .getOrElse(orderedMedia)
        }

        val result = query.q match {
          case Some(query) => col.filter(_.uri.toLowerCase.contains(query.toLowerCase))
          case None        => col
        }

        val offset = query.offset.getOrElse(0)
        val end    = Math.min(offset + query.n, result.size)

        val videos = if (offset > result.size) Nil else result.slice(offset, end)

        Effect.reply(sender)(SearchResult(offset, result.size, videos))

      case SetThumbnail(id, timeStamp, sender) =>
        state.media.get(id) match {
          case None =>
            Effect.reply(sender)(None)

          case Some(vid) =>
            val sanitizedTimeStamp = Math.max(0, Math.min(vid.duration, timeStamp))
            val videoPath          = vid.path(config.libraryPath)

            val previews           = List(
              Preview(
                timestampStart = sanitizedTimeStamp,
                timestampEnd = sanitizedTimeStamp + 3000
              )
            )

            val newVid             = vid.copy(thumbnailTimestamp = sanitizedTimeStamp, previews = previews)

            deleteThumbnailAtTimestamp(config.indexPath, vid.id, vid.thumbnailTimestamp)

            Effect
              .persist(MediaUpdated(id, newVid))
              .thenRun((s: State) => generateThumbnail(videoPath, config.indexPath, id, sanitizedTimeStamp))
              .thenReply(sender)(_ => Some(newVid))
        }
    }
  }
}
