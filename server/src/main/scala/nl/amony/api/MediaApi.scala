package nl.amony.api

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import nl.amony.actor.Message
import nl.amony.actor.media.MediaLibProtocol.{AddFragment, DeleteFragment, ErrorResponse, GetAll, GetById, Media, RemoveMedia, UpdateFragmentRange, UpdateFragmentTags, UpdateMetaData, UpsertMedia}

import scala.concurrent.{ExecutionContext, Future}
import akka.actor.typed.scaladsl.AskPattern._

class MediaApi(system: ActorSystem[Message]) {

  implicit val scheduler            = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  def getById(id: String)(implicit timeout: Timeout): Future[Option[Media]] =
    system.ask[Option[Media]](ref => GetById(id, ref))

  def getAll()(implicit timeout: Timeout): Future[List[Media]] =
    system.ask[List[Media]](ref => GetAll(ref))

  def upsertMedia(media: Media)(implicit timeout: Timeout): Future[Media] =
    system.ask[Boolean](ref => UpsertMedia(media, ref)).map(_ => media)

  def deleteMedia(id: String, deleteResource: Boolean)(implicit timeout: Timeout): Future[Boolean] =
    system.ask[Boolean](ref => RemoveMedia(id, deleteResource, ref))

  def updateMetaData(id: String, title: Option[String], comment: Option[String], tags: List[String])(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    system.ask[Either[ErrorResponse, Media]](ref => UpdateMetaData(id, title, comment, tags.toSet, ref))

  def addFragment(mediaId: String, from: Long, to: Long)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    system.ask[Either[ErrorResponse, Media]](ref => AddFragment(mediaId, from, to, ref))

  def updateFragmentRange(mediaId: String, idx: Int, from: Long, to: Long)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    system.ask[Either[ErrorResponse, Media]](ref => UpdateFragmentRange(mediaId, idx, from, to, ref))

  def updateFragmentTags(id: String, idx: Int, tags: List[String])(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    system.ask[Either[ErrorResponse, Media]](ref => UpdateFragmentTags(id, idx, tags, ref))

  def deleteFragment(id: String, idx: Int)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    system.ask[Either[ErrorResponse, Media]](ref => DeleteFragment(id, idx, ref))
}
