package nl.amony.actor

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import better.files.File
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaLibEventSourcing._
import nl.amony.actor.MediaLibProtocol._
import nl.amony.lib.MediaScanner
import scribe.Logging

import java.awt.Desktop

object MediaLibCommandHandler extends Logging {

  def apply(config: MediaLibConfig, scanner: MediaScanner)(state: State, cmd: Command): Effect[Event, State] = {

    logger.debug(s"Received command: $cmd")

    def requireMedia[T](mediaId: String, sender: ActorRef[Either[ErrorResponse, T]])(
        effect: Media => Effect[Event, State]
    ): Effect[Event, State] = {
      state.media.get(mediaId) match {
        case None        => Effect.reply(sender)(Left(MediaNotFound(mediaId)))
        case Some(media) => effect(media)
      }
    }

    def invalidCommand[T](sender: ActorRef[Either[ErrorResponse, T]], reason: String): Effect[Event, State] = {
      Effect.reply(sender)(Left(InvalidCommand(reason)))
    }

    // format: off
    def verifyFragmentRange(start: Long, end: Long, mediaDuration: Long): Either[String, Unit] = {
      if (start >= end)
        Left(s"Invalid range ($start -> $end): start must be before end")
      else if (start < 0 || end > mediaDuration)
        Left(s"Invalid range ($start -> $end): valid range is from 0 to $mediaDuration")
      else if (end - start > config.maximumFragmentLength.toMillis)
        Left(s"Fragment length is larger then maximum allowed: ${end - start} > ${config.minimumFragmentLength.toMillis}")
      else if (end - start < config.minimumFragmentLength.toMillis)
        Left(s"Fragment length is smaller then minimum allowed: ${end - start} < ${config.minimumFragmentLength.toMillis}")
      else
        Right(())
    }
    // format: on

    cmd match {

      case UpsertMedia(media, sender) =>
        logger.debug(s"Adding new media: ${media.fileInfo.relativePath}")

        Effect
          .persist(MediaAdded(media))
          .thenReply(sender)(_ => true)

      case UpdateMetaData(mediaId, title, comment, tags, sender) =>
        requireMedia(mediaId, sender) { media =>
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

      case RemoveMedia(mediaId, deleteFile, sender) =>
        val media = state.media(mediaId)
        val path  = media.resolvePath(config.mediaPath)

        logger.info(s"Deleting media '$mediaId:${media.fileInfo.relativePath}'")

        Effect
          .persist(MediaRemoved(mediaId))
          .thenRun((s: State) => {
            if (deleteFile && File(path).exists)
              Desktop.getDesktop().moveToTrash(path.toFile());
          })
          .thenReply(sender)(_ => true)

      case GetById(id, sender) =>
        Effect.reply(sender)(state.media.get(id))

      case GetAll(sender) =>
        Effect.reply(sender)(state.media.values.toList)

      case DeleteFragment(id, idx, sender) =>
        requireMedia(id, sender) { media =>

          if (idx < 0 || idx >= media.fragments.size)
            invalidCommand(sender, s"Index out of bounds ($idx): valid range is from 0 to ${media.fragments.size - 1}")
          else {
            logger.info(s"Deleting fragment $id:$idx")
            Effect
              .persist(FragmentDeleted(id, idx))
              .thenRun { (_: State) =>
                scanner.deleteVideoFragment(
                  media,
                  media.fragments(idx).fromTimestamp,
                  media.fragments(idx).toTimestamp,
                  config.previews
                )
              }
              .thenReply(sender)(s => Right(s.media(id)))
          }
        }

      case UpdateFragmentRange(id, idx, start, end, sender) =>
        requireMedia(id, sender) { media =>
          if (idx < 0 || idx >= media.fragments.size) {
            invalidCommand(sender, s"Index out of bounds ($idx): valid range is from 0 to ${media.fragments.size - 1}")
          }
          else
            verifyFragmentRange(start, end, media.videoInfo.duration) match {
              case Left(error) => Effect.reply(sender)(Left(InvalidCommand(error)))
              case Right(_)    =>

                logger.info(s"Updating fragment $id:$idx to $start:$end")
                val oldFragment = media.fragments(idx)

                Effect
                  .persist(FragmentRangeUpdated(id, idx, start, end))
                  .thenRun { (s: State) =>
                    scanner.deleteVideoFragment(
                      media,
                      oldFragment.fromTimestamp,
                      oldFragment.toTimestamp,
                      config.previews
                    )
                    scanner.createPreviews(
                      media,
                      media.resolvePath(config.mediaPath),
                      start,
                      end,
                      config.previews
                    )
                  }
                  .thenReply(sender)(s => Right(s.media(id)))
            }
        }

      case AddFragment(id, start, end, sender) =>
        requireMedia(id, sender) { media =>
          logger.info(s"Adding fragment for $id from $start to $end")

          verifyFragmentRange(start, end, media.videoInfo.duration) match {
            case Left(error) => Effect.reply(sender)(Left(InvalidCommand(error)))
            case Right(_)    =>
              Effect
                .persist(FragmentAdded(id, start, end))
                .thenRun((_: State) =>
                  scanner.createPreviews(
                    media,
                    media.resolvePath(config.mediaPath),
                    start,
                    end,
                    config.previews
                  )
                )
                .thenReply(sender)(s => Right(s.media(id)))
          }
        }

      case UpdateFragmentTags(id, index, tags, sender) =>
        requireMedia(id, sender) { media =>
          val frag        = media.fragments(index)
          val tagsAdded   = tags.toSet -- frag.tags
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
