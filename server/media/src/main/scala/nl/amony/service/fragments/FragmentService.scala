package nl.amony.service.fragments

import akka.actor.typed.ActorSystem
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.service.media.MediaConfig.FragmentSettings
import nl.amony.service.media.actor.MediaLibProtocol.{ErrorResponse, Media, MediaCommand}
import scribe.Logging

import scala.concurrent.Future

case class Fragment(start: Long, end: Long, comment: Option[String], tags: List[String])

sealed trait Event

object Events {

//  def eventSource(state: Map[String, List[Fragment]], e: Event) = e match {
//
//    case FragmentAdded(id, from, to) =>
//      val media        = state.media(id)
//      val newFragments = Fragment(from, to, None, Nil) :: media.fragments
//
//      state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))
//
//    case FragmentRangeUpdated(id, index, from, to) =>
//      val media = state.media(id)
//
//      // check if specific range already exists
//      val oldFragment      = media.fragments(index)
//      val newFragment      = oldFragment.copy(fromTimestamp = from, toTimestamp = to)
//      val primaryThumbnail = if (index == 0) from else media.thumbnailTimestamp
//      val newMedia =
//        media.copy(
//          thumbnailTimestamp = primaryThumbnail,
//          fragments          = media.fragments.replaceAtPos(index, newFragment)
//        )
//
//      state.copy(media = state.media + (media.id -> newMedia))
//
//    case FragmentDeleted(id, index) =>
//      val media        = state.media(id)
//      val newFragments = media.fragments.deleteAtPos(index)
//
//      state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))
//
//    case FragmentMetaDataUpdated(mediaId, index, comment, tagsAdded, tagsRemoved) =>
//      val media           = state.media(mediaId)
//      val fragment        = media.fragments(index)
//      val tags            = (fragment.tags.toSet -- tagsRemoved ++ tagsAdded).toList
//      val fragmentUpdated = fragment.copy(tags = tags, comment = comment.orElse(fragment.comment))
//      val newFragments    = media.fragments.replaceAtPos(index, fragmentUpdated)
//
//      state.copy(media = state.media + (mediaId -> media.copy(fragments = newFragments)))
//  }
}

case class FragmentDeleted(mediaId: String, index: Int)                                              extends Event
case class FragmentAdded(mediaId: String, fromTimeStamp: Long, toTimestamp: Long)                    extends Event
case class FragmentRangeUpdated(mediaId: String, index: Int, fromTimestamp: Long, toTimestamp: Long) extends Event
case class FragmentMetaDataUpdated(
                                    mediaId: String,
                                    index: Int,
                                    comment: Option[String],
                                    tagsAdded: Set[String],
                                    tagsRemoved: Set[String]
                                  ) extends Event

class FragmentStore {
  // format: off
  def verifyFragmentRange(fragmentSettings: FragmentSettings, start: Long, end: Long, mediaDuration: Long): Either[String, Unit] = {
    if (start >= end)
      Left(s"Invalid range ($start -> $end): start must be before end")
    else if (start < 0 || end > mediaDuration)
      Left(s"Invalid range ($start -> $end): valid range is from 0 to $mediaDuration")
    else if (end - start > fragmentSettings.maximumFragmentLength.toMillis)
      Left(s"Fragment length is larger then maximum allowed: ${end - start} > ${fragmentSettings.minimumFragmentLength.toMillis}")
    else if (end - start < fragmentSettings.minimumFragmentLength.toMillis)
      Left(s"Fragment length is smaller then minimum allowed: ${end - start} < ${fragmentSettings.minimumFragmentLength.toMillis}")
    else
      Right(())
  }
  // format: on
}


class FragmentService(system: ActorSystem[Nothing]) extends AkkaServiceModule(system) with Logging {

  def getFragments(mediaId: String, userId: String): List[Fragment] = ???

  def addFragment(mediaId: String, userId: String, index: Int, range: (Long, Long)): Unit = ???

  def addFragment(mediaId: String, from: Long, to: Long): Future[Either[ErrorResponse, Media]] = ???

  def updateFragmentRange(mediaId: String, idx: Int, from: Long, to: Long): Future[Either[ErrorResponse, Media]] = ???

  def updateFragmentTags(id: String, idx: Int, tags: List[String]): Future[Either[ErrorResponse, Media]] = ???

  def deleteFragment(id: String, idx: Int): Future[Either[ErrorResponse, Media]] = ???
}
