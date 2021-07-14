package com.github.merlijn.kagera.actor

import akka.persistence.typed.scaladsl.Effect
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
        Effect.reply(sender)(state.media.get(id))

      case GetCollections(sender) =>
        Effect.reply(sender)(state.collections.values.toList.sortBy(_.title))

      case GetAll(sender) =>
        Effect.reply(sender)(state.media.values.toList)

      case Search(query, sender) =>
        val col = query.c match {
          case None => state.media.values
          case Some(id) =>
            state.collections
              .get(id)
              .map { cid =>
                state.media.values.filter(_.uri.startsWith(cid.title.substring(1)))
              }
              .getOrElse(state.media.values)
        }

        val result = query.q match {
          case Some(query) => col.filter(_.uri.toLowerCase.contains(query.toLowerCase))
          case None        => col
        }

        val offset = query.offset.getOrElse(0)
        val end    = Math.min(offset + query.n, result.size)

        val videos = if (offset > result.size) Nil else result.slice(offset, end)

        Effect.reply(sender)(SearchResult(offset, result.size, videos.toList))

      case SetThumbnail(id, timeStamp, sender) =>
        state.media.get(id) match {
          case None =>
            Effect.reply(sender)(None)

          case Some(vid) =>
            val sanitizedTimeStamp = Math.max(0, Math.min(vid.duration, timeStamp))
            val videoPath          = vid.path(config.libraryPath)
            val newVid = vid.copy(thumbnail = Thumbnail(timeStamp))

            Effect
              .persist(MediaUpdated(id, newVid))
              .thenRun((s: State) => generateThumbnail(videoPath, config.indexPath, id, sanitizedTimeStamp))
              .thenReply(sender)(_ => Some(newVid))
        }
    }
}
