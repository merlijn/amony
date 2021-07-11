package com.github.merlijn.kagera.actor

import akka.persistence.typed.scaladsl.Effect
import better.files.File
import com.github.merlijn.kagera.lib.MediaLibScanner.generateThumbnail
import com.github.merlijn.kagera.actor.MediaLibEventSourcing._
import com.github.merlijn.kagera.actor.MediaLibActor._
import com.github.merlijn.kagera.lib.MediaLibConfig

object MediaLibCommandHandler {

  def apply(config: MediaLibConfig)(state: State, cmd: Command): Effect[Event, State] =
    cmd match {

      case AddMedia(media) =>
        Effect.persist(MediaAdded(media))

      case AddCollections(collections) =>
        Effect.persist(CollectionsAdded(collections))

      case GetById(id, sender) =>
        Effect.reply(sender)(state.media.find(_.id == id))

      case GetCollections(sender) =>
        Effect.reply(sender)(state.collections)

      case GetAll(sender) =>
        Effect.reply(sender)(state.media)

      case Search(query, sender) =>
        val col = query.c match {
          case None => state.media
          case Some(id) =>
            state.collections
              .find(_.id == id)
              .map { cid =>
                state.media.filter(_.uri.startsWith(cid.title.substring(1)))
              }
              .getOrElse(state.media)
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
        state.media.find(_.id == id) match {
          case None =>
            Effect.reply(sender)(None)

          case Some(vid) =>
            val sanitizedTimeStamp = Math.max(0, Math.min(vid.duration, timeStamp))
            val videoPath          = vid.path(config.libraryPath)

            File(vid.thumbnailPath(config.indexPath.resolve("thumbnails"))).delete()

            val newThumbnail =
              generateThumbnail(videoPath, config.indexPath, id, sanitizedTimeStamp)

            val newVid = vid.copy(thumbnail = Thumbnail(timeStamp, newThumbnail))

            Effect
              .persist(ReplaceVid(id, newVid))
              .thenReply(sender)(_ => Some(newVid))
        }
    }
}
