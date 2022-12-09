package nl.amony.service.fragments

import akka.actor.typed.ActorSystem
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.service.fragments.FragmentProtocol.Fragment
import nl.amony.service.resources.ResourceConfig.FragmentSettings
import scribe.Logging

import scala.concurrent.Future

object FragmentProtocol {
  case class Fragment(mediaId: String, range: (Long, Long), comment: Option[String], tags: List[String])

  implicit class ListOps[T](list: List[T]) {
    def replaceAtPos(idx: Int, e: T): List[T] = {
      list.slice(0, idx) ::: (e :: list.slice(idx + 1, list.size))
    }

    def deleteAtPos(idx: Int): List[T] = {
      list.slice(0, idx) ::: list.slice(idx + 1, list.size)
    }
  }
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

  def getFragments(mediaId: String, userId: String): Future[List[Fragment]] = Future.successful(List.empty)

  def addFragment(mediaId: String, userId: String, index: Int, range: (Long, Long)): Unit = ???

  def addFragment(mediaId: String, from: Long, to: Long): Future[Fragment] = ???

  def updateFragmentRange(mediaId: String, idx: Int, from: Long, to: Long): Future[Fragment] = ???

  def updateFragmentTags(mediaId: String, idx: Int, tags: List[String]): Future[Fragment] = ???

  def deleteFragment(mediaId: String, idx: Int): Future[Unit] = ???
}
