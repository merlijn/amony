package nl.amony.service.fragments

import akka.actor.typed.ActorSystem
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.service.fragments.Protocol.Fragment
import nl.amony.service.resources.ResourceConfig.FragmentSettings
import nl.amony.service.media.actor.MediaLibProtocol.{GetById, Media, MediaCommand}
import scribe.Logging

import scala.concurrent.Future

object Protocol {
  case class Fragment(mediaId: String, start: Long, end: Long, comment: Option[String], tags: List[String])
}

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

  def getFragments(mediaId: String, userId: String): Future[List[Fragment]] =
    ask[MediaCommand, Option[Media]](ref => GetById(mediaId, ref)).map(_.toList.flatMap(_.fragments))

  def addFragment(mediaId: String, userId: String, index: Int, range: (Long, Long)): Unit = ???

  def addFragment(mediaId: String, from: Long, to: Long): Future[Fragment] = ???

  def updateFragmentRange(mediaId: String, idx: Int, from: Long, to: Long): Future[Fragment] = ???

  def updateFragmentTags(mediaId: String, idx: Int, tags: List[String]): Future[Fragment] = ???

  def deleteFragment(mediaId: String, idx: Int): Future[Unit] = ???
}
