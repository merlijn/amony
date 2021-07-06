package com.github.merlijn.webapp.actor

import akka.persistence.typed.scaladsl.Effect
import better.files.File
import com.github.merlijn.webapp.MediaLibApi.generateThumbnail
import com.github.merlijn.webapp.MediaLibConfig
import com.github.merlijn.webapp.Model.SearchResult
import com.github.merlijn.webapp.actor.MediaLibActor._

object MediaLibHandler {

  def apply(config: MediaLibConfig)(state: State, cmd: Command): Effect[Event, State] =

    cmd match {

      case AddMedia(media) =>
        Effect.persist(MediaAdded(media))

      case AddCollections(collections) =>
        Effect.persist(CollectionsAdded(collections))

      case GetById(id, sender) =>
        Effect.reply(sender)(state.media.find(_.id == id))

      case Search(query, sender) =>
        val col = query.c match {
          case None     => state.media
          case Some(id) =>
            state.collections.find(_.id == id).map { cid =>
              state.media.filter(_.fileName.startsWith(cid.name.substring(1)))
            }.getOrElse(state.media)
        }

        val result = query.q match {
          case Some(query) => col.filter(_.fileName.toLowerCase.contains(query.toLowerCase))
          case None        => col
        }

        val start = (query.page - 1) * query.size
        val end = Math.min(result.size, query.page * query.size)

        val videos = if (start > result.size) Nil else result.slice(start, end)

        Effect.reply(sender)(SearchResult(query.page, query.size, result.size, videos))

      case SetThumbnail(id, timeStamp, sender) =>

        state.media.find(_.id == id) match {
          case None =>
            Effect.reply(sender)(None)

          case Some(vid) =>
            val sanitizedTimeStamp = Math.max(0, Math.min(vid.duration, timeStamp))
            val videoPath = vid.path(config.libraryPath)

            File(vid.thumbnailPath(config.indexPath)).delete()

            val newThumbnail = generateThumbnail(videoPath, config.indexPath, id, sanitizedTimeStamp)

            val newVid = vid.copy(
              thumbnail = s"/files/thumbnails/$newThumbnail"
            )

            Effect
              .persist(ReplaceVid(id, newVid))
              .thenReply(sender)(_ => Some(newVid))
        }
    }
}
