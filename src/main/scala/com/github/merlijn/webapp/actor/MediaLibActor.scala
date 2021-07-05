package com.github.merlijn.webapp.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import better.files.File
import com.github.merlijn.webapp.MediaLib.generateThumbnail
import com.github.merlijn.webapp.{Logging, MediaLibConfig}
import com.github.merlijn.webapp.Model.{Collection, SearchResult, Video}

object MediaLibActor extends Logging {

  sealed trait Command

  case class GetById(id: String, sender: ActorRef[Option[Video]]) extends Command

  case class Query(q: Option[String], page: Int, size: Int, c: Option[Int])
  case class Search(query: Query, sender: ActorRef[SearchResult]) extends Command

  case class SetThumbnail(id: String, timeStamp: Long, sender: ActorRef[Option[Video]]) extends Command

  def apply(config: MediaLibConfig, media: List[Video], collections: List[Collection]): Behavior[Command] =

    Behaviors.receive { (context, message) =>
      message match {

        case GetById(id, sender) =>
          sender.tell(media.find(_.id == id))
          Behaviors.same

        case Search(query, sender) =>
          val col = query.c match {
            case None     => media
            case Some(id) =>
              collections.find(_.id == id).map { cid =>
                media.filter(_.fileName.startsWith(cid.name.substring(1)))
              }.getOrElse(media)
          }

          val result = query.q match {
            case Some(query) => col.filter(_.fileName.toLowerCase.contains(query.toLowerCase))
            case None        => col
          }

          val start = (query.page - 1) * query.size
          val end = Math.min(result.size, query.page * query.size)

          val videos = if (start > result.size) Nil else result.slice(start, end)

          sender.tell(SearchResult(query.page, query.size, result.size, videos))

          Behaviors.same

        case SetThumbnail(id, timeStamp, sender) =>

          media.find(_.id == id) match {
            case None =>
              sender.tell(None)
              Behaviors.same

            case Some(vid) =>
              val sanitizedTimeStamp = Math.max(0, Math.min(vid.duration, timeStamp))
              val videoPath = vid.path(config.libraryPath)

              File(vid.thumbnailPath(config.indexPath)).delete()

              val newThumbnail = generateThumbnail(videoPath, config.indexPath, id, sanitizedTimeStamp)

              val newVid = vid.copy(
                thumbnail = s"/files/thumbnails/$newThumbnail"
              )

              sender.tell(Some(newVid))

              // replace vid
              val idx = media.indexWhere(_.id == id)
              val newMedia = media.slice(0, idx) ::: (newVid :: media.slice(idx + 1, media.size))

              MediaLibActor(config, newMedia, collections)
          }
      }
  }
}
