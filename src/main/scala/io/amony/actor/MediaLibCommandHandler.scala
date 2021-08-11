package io.amony.actor

import akka.persistence.typed.scaladsl.Effect
import better.files.File
import io.amony.MediaLibConfig
import io.amony.actor.MediaLibActor._
import io.amony.actor.MediaLibEventSourcing._
import io.amony.lib.ListOps
import io.amony.lib.MediaLibScanner.{createVideoFragment, deleteVideoFragment}
import scribe.Logging

import java.awt.Desktop

object MediaLibCommandHandler extends Logging {

  def apply(config: MediaLibConfig)(state: State, cmd: Command): Effect[Event, State] = {

    lazy val tags: List[Collection] = {

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

      case UpsertMedia(media, sender) =>
        logger.debug(s"Adding new media: ${media.uri}")
        Effect
          .persist(MediaAdded(media))
          .thenReply(sender)(_ => true)

      case RemoveMedia(mediaId, sender) =>

        val media = state.media(mediaId)
        val path = media.resolvePath(config.libraryPath)

        logger.info(s"Deleting media '$mediaId:/${media.uri}'")

        Effect
          .persist(MediaRemoved(mediaId))
          .thenRun( (s: State) => {
            if (File(path).exists)
              Desktop.getDesktop().moveToTrash(path.toFile());
          })
          .thenReply(sender)(_ => true)

      case GetById(id, sender) =>
        Effect.reply(sender)(state.media.get(id))

      case GetTags(sender) =>
        Effect.reply(sender)(tags.sortBy(_.title))

      case GetAll(sender) =>
        Effect.reply(sender)(state.media.values.toList)

      case Search(query, sender) =>
        val tagsFiltered = query.tag match {
          case None => orderedMedia
          case Some(id) =>
            tags
              .find(_.id == id)
              .map { tagId =>
                orderedMedia.filter(_.uri.startsWith(tagId.title.substring(1)))
              }
              .getOrElse(orderedMedia)
        }

        val result = query.q match {
          case Some(query) => tagsFiltered.filter(_.uri.toLowerCase.contains(query.toLowerCase))
          case None        => tagsFiltered
        }

        val offset = query.offset.getOrElse(0)
        val end    = Math.min(offset + query.n, result.size)

        val videos = if (offset > result.size) Nil else result.slice(offset, end)

        Effect.reply(sender)(SearchResult(offset, result.size, videos))

      case DeleteFragment(id, idx, sender) =>
        state.media.get(id) match {

          case None =>
            Effect.reply(sender)(Left(InvalidCommand(s"No video found with id '$id'")))

          case Some(vid) =>
            logger.info(s"Deleting fragment $id:$idx")

            val newFragments = vid.fragments.deleteAtPos(idx)
            val newVid =
              vid.copy(
                fragments = vid.fragments.deleteAtPos(idx),
                thumbnailTimestamp = newFragments(0).fromTimestamp)

            Effect
              .persist(MediaUpdated(id, newVid))
              .thenRun { (s: State) =>
                deleteVideoFragment(
                  config.indexPath,
                  vid.id,
                  vid.fragments(idx).fromTimestamp,
                  vid.fragments(idx).toTimestamp
                )
              }
              .thenReply(sender)(_ => Right(newVid))
        }

      case UpdateFragmentRange(id, idx, from, to, sender) =>
        state.media.get(id) match {

          case None =>
            Effect.reply(sender)(Left(InvalidCommand(s"No video found with id '$id'")))

          case Some(vid) =>

            logger.info(s"Updating fragment $id:$idx to $from:$to")

            if (idx < 0 || idx >= vid.fragments.size) {
              Effect.reply(sender)(Left(InvalidCommand("Index out of bounds")))
            } else {
              // check if specific range already exists
              val oldFragment      = vid.fragments(idx)
              val newFragment      = oldFragment.copy(fromTimestamp = from, toTimestamp = to)
              val primaryThumbnail = if (idx == 0) from else vid.thumbnailTimestamp
              val newVid =
                vid.copy(
                  thumbnailTimestamp = primaryThumbnail,
                  fragments = vid.fragments.replaceAtPos(idx, newFragment))

              Effect
                .persist(MediaUpdated(id, newVid))
                .thenRun { (s: State) =>
                  deleteVideoFragment(config.indexPath, vid.id, oldFragment.fromTimestamp, oldFragment.toTimestamp)
                  createVideoFragment(vid.resolvePath(config.libraryPath), config.indexPath, id, from, to)
                }
                .thenReply(sender)(_ => Right(newVid))
            }
        }

      case AddFragment(id, from, to, sender) =>
        state.media.get(id) match {
          case None =>
            Effect.reply(sender)(Left(InvalidCommand(s"No video found with id '$id'")))

          case Some(vid) =>
            logger.info(s"Adding fragment for $id from $from to $to")

            if (from > to) {
              val msg = s"from: $from > to: $to"
              logger.warn(msg)
              Effect.none.thenReply(sender)(_ => Left(InvalidCommand(msg)))
            } else if (to > vid.duration) {
              val msg = s"to: $to > duration: ${vid.duration}"
              logger.warn(msg)
              Effect.none.thenReply(sender)(_ => Left(InvalidCommand(msg)))
            } else {

              Effect
                .persist(FragmentAdded(id, from, to))
                .thenRun((_: State) => createVideoFragment(vid.resolvePath(config.libraryPath), config.indexPath, id, from, to))
                .thenReply(sender)(s => Right(s.media(id)))
            }
        }

      case UpdateFragmentTags(id, fragmentIndex, tags, sender) =>
        logger.info(s"Received AddFragmentTag command: $id:$fragmentIndex -> ${tags.mkString(",")}")

        Effect.persist(FragmentTagsUpdated(id, fragmentIndex, tags))
          .thenReply(sender)(_ => Right(state.media(id)))
    }
  }
}
