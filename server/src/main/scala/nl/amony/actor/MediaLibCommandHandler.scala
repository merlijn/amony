package nl.amony.actor

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import better.files.File
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaLibProtocol._
import nl.amony.actor.MediaLibEventSourcing._
import nl.amony.lib.MediaLibScanner.{createVideoFragment, deleteVideoFragment}
import scribe.Logging

import java.awt.Desktop

object MediaLibCommandHandler extends Logging {

  def apply(config: MediaLibConfig)(state: State, cmd: Command): Effect[Event, State] = {

    lazy val directories: List[Directory] = {

      val dirs = state.media.values.foldLeft(Set.empty[String]) { case (set, e) =>
        val parent = (File(config.libraryPath) / e.fileInfo.relativePath).parent
        val dir    = s"/${config.libraryPath.relativize(parent)}"
        set + dir
      }

      dirs.toList.sorted.zipWithIndex.map { case (path, idx) => Directory(idx.toString, path) }
    }

    lazy val sortedByFilename  = state.media.values.toList.sortBy(m => m.title.getOrElse(m.fileName()))
    lazy val sortedByDateAdded = state.media.values.toList.sortBy(m => m.fileInfo.creationTime)
    lazy val sortedByDuration  = state.media.values.toList.sortBy(m => m.videoInfo.duration)

    logger.debug(s"Received command: $cmd")

    def requireMedia[T](mediaId: String, sender: ActorRef[Either[ErrorResponse, T]])
                       (effect: Media => Effect[Event, State]): Effect[Event, State] = {
      state.media.get(mediaId) match {
        case None        => Effect.reply(sender)(Left(MediaNotFound(mediaId)))
        case Some(media) => effect(media)
      }
    }

    cmd match {

      case UpsertMedia(media, sender) =>
        logger.debug(s"Adding new media: ${media.fileInfo.relativePath}")
        Effect
          .persist(MediaAdded(media))
          .thenReply(sender)(_ => true)

      case UpdateMetaData(mediaId, title, comment, tags, sender) =>

        requireMedia(mediaId, sender){ media =>

          val titleUpdate   = if (title == media.title) None else title
          val commentUpdate = if (comment == media.comment) None else comment
          val tagsAdded     = tags -- media.tags
          val tagsRemoved   = media.tags -- tags

          if (tagsAdded.isEmpty && tagsRemoved.isEmpty && titleUpdate.isEmpty && commentUpdate.isEmpty)
            Effect.reply(sender)(Right(media))
          else
            Effect
              .persist(MediaMetaDataUpdated(mediaId, titleUpdate, commentUpdate, tagsAdded, tagsRemoved))
              .thenReply(sender)(s => Right(s.media(mediaId)))
        }

      case RemoveMedia(mediaId, sender) =>

        val media = state.media(mediaId)
        val path  = media.resolvePath(config.libraryPath)

        logger.info(s"Deleting media '$mediaId:${media.fileInfo.relativePath}'")

        Effect
          .persist(MediaRemoved(mediaId))
          .thenRun((s: State) => {
            if (File(path).exists)
              Desktop.getDesktop().moveToTrash(path.toFile());
          })
          .thenReply(sender)(_ => true)

      case GetById(id, sender) =>
        Effect.reply(sender)(state.media.get(id))

      case GetDirectories(sender) =>
        Effect.reply(sender)(directories.sortBy(_.path))

      case GetAll(sender) =>
        Effect.reply(sender)(state.media.values.toList)

      case Search(query, sender) =>
        val dir = query.directory.flatMap(t => directories.find(_.id == t))

        def filterDir(m: Media): Boolean =
          dir.map(t => m.fileInfo.relativePath.startsWith(t.path.substring(1))).getOrElse(true)
        def filterRes(m: Media): Boolean = query.minRes.map(res => m.videoInfo.resolution._2 >= res).getOrElse(true)
        def filterQuery(m: Media): Boolean =
          query.q.map(q => m.fileInfo.relativePath.toLowerCase.contains(q.toLowerCase)).getOrElse(true)

        def filterMedia(m: Media): Boolean = filterDir(m) && filterRes(m) && filterQuery(m)

        val unfiltered = query.sort match {
          case None                             => state.media.values
          case Some(Sort(FileName, false))      => sortedByFilename
          case Some(Sort(FileName, true))       => sortedByFilename.reverse
          case Some(Sort(DateAdded, false))     => sortedByDateAdded
          case Some(Sort(DateAdded, true))      => sortedByDateAdded.reverse
          case Some(Sort(VideoDuration, false)) => sortedByDuration
          case Some(Sort(VideoDuration, true))  => sortedByDuration.reverse
        }

        val result = unfiltered.filter(filterMedia)

        val offset = query.offset.getOrElse(0)
        val end    = Math.min(offset + query.n, result.size)

        val videos = if (offset > result.size) Nil else result.slice(offset, end)

        Effect.reply(sender)(SearchResult(offset, result.size, videos.toList))

      case DeleteFragment(id, idx, sender) =>

        requireMedia(id, sender) { media =>
          logger.info(s"Deleting fragment $id:$idx")

          if (idx < 0 || idx >= media.fragments.size)
            Effect.reply(sender)(
              Left(InvalidCommand(s"Index out of bounds ($idx): valid range is from 0 to ${media.fragments.size - 1}"))
            )
          else
            Effect
              .persist(FragmentDeleted(id, idx))
              .thenRun { (_: State) =>
                deleteVideoFragment(
                  config.indexPath,
                  media.id,
                  media.fragments(idx).fromTimestamp,
                  media.fragments(idx).toTimestamp
                )
              }
              .thenReply(sender)(s => Right(s.media(id)))
        }

      case UpdateFragmentRange(id, idx, from, to, sender) =>

        requireMedia(id, sender) { media =>
          logger.info(s"Updating fragment $id:$idx to $from:$to")

          if (idx < 0 || idx >= media.fragments.size) {
            Effect.reply(sender)(
              Left(InvalidCommand(s"Index out of bounds ($idx): valid range is from 0 to ${media.fragments.size - 1}"))
            )
          } else if (from < 0 || from >= to || to > media.videoInfo.duration)
            Effect.reply(sender)(
              Left(
                InvalidCommand(s"Invalid range ($from -> $to): valid range is from 0 to ${media.videoInfo.duration}")
              )
            )
          else {

            val oldFragment = media.fragments(idx)

            Effect
              .persist(FragmentRangeUpdated(id, idx, from, to))
              .thenRun { (s: State) =>
                deleteVideoFragment(config.indexPath, media.id, oldFragment.fromTimestamp, oldFragment.toTimestamp)
                createVideoFragment(media.resolvePath(config.libraryPath), config.indexPath, id, from, to, config.previews)
              }
              .thenReply(sender)(s => Right(s.media(id)))
          }
        }

      case AddFragment(id, from, to, sender) =>

        requireMedia(id, sender){ media =>
          logger.info(s"Adding fragment for $id from $from to $to")

          if (from > to) {
            val msg = s"from: $from > to: $to"
            logger.warn(msg)
            Effect.reply(sender)(Left(InvalidCommand(msg)))
          } else if (to > media.videoInfo.duration) {
            val msg = s"to: $to > duration: ${media.videoInfo.duration}"
            logger.warn(msg)
            Effect.reply(sender)(Left(InvalidCommand(msg)))
          } else {

            Effect
              .persist(FragmentAdded(id, from, to))
              .thenRun((_: State) =>
                createVideoFragment(media.resolvePath(config.libraryPath), config.indexPath, id, from, to, config.previews)
              )
              .thenReply(sender)(s => Right(s.media(id)))
          }
        }

      case UpdateFragmentTags(id, index, tags, sender) =>

        requireMedia(id, sender) { media =>

          val frag = media.fragments(index)
          val tagsAdded = tags.toSet -- frag.tags
          val tagsRemoved = frag.tags.toSet -- tags

          if (tagsAdded.isEmpty && tagsRemoved.isEmpty)
            Effect.reply(sender)(Right(media))
          else
            Effect
              .persist(FragmentMetaDataUpdated(id, index, None, tagsAdded, tagsRemoved))
              .thenReply(sender)(_ => Right(state.media(id)))
        }
    }
  }
}