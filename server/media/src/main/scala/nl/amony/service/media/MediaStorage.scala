package nl.amony.service.media

import nl.amony.service.media.MediaStorage.{MediaRow, asRow, fromRow}
import nl.amony.service.media.api._
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.Future

object MediaStorage {

  type MediaRow =
    (String, String, Long, Long,
     String, String, String, Long, Float, Long,
     String, Int, Int, Option[String], Option[String], Option[String])

  def asRow(media: Media): MediaRow =
    (media.mediaId, media.userId, media.createdTimestamp, media.thumbnailTimestamp,
      media.resourceInfo.bucketId, media.resourceInfo.hash, media.resourceInfo.relativePath, media.resourceInfo.sizeInBytes,
      media.mediaInfo.fps, media.mediaInfo.durationInMillis, media.mediaInfo.mediaType, media.width, media.height,
      media.meta.title, media.meta.comment, Option.when(media.meta.tags.nonEmpty)(media.meta.tags.mkString(",")))

  def fromRow(row: MediaRow): Media = row match {
    case (mediaId, userId, uploadTimestamp, thumbnailTimestamp,
          resourceBucketId, resourceHash, resourcePath, resourceSize,
          videoFps, videoDuration, mediaType, mediaWidth, mediaHeight,
          title, comment, tags) =>

      val resourceInfo = ResourceInfo(resourceBucketId, resourcePath, resourceHash, resourceSize)
      val mediaInfo = MediaInfo(mediaType, mediaWidth, mediaHeight, videoFps, videoDuration)
      val meta = MediaMeta(title, comment, tags.toList.flatMap(_.split(",")))

      Media(mediaId, mediaType, userId, uploadTimestamp, thumbnailTimestamp, meta, mediaInfo, resourceInfo)
  }
}

class MediaStorage[P <: JdbcProfile](dbConfig: DatabaseConfig[P]) extends Logging {

  import dbConfig.profile.api._

  private class MediaTable(tag: Tag) extends Table[MediaRow](tag, "media") {

    def mediaId            = column[String]("key", O.PrimaryKey) // This is the primary key column

    def uploader           = column[String]("upload_user_id")
    def uploadTimestamp    = column[Long]("upload_timestamp")
    def thumbnailTimestamp = column[Long]("thumbnail_timestamp")

    def resourceBucketId   = column[String]("resource_bucket_id")
    def resourceHash       = column[String]("resource_hash")
    def resourcePath       = column[String]("resource_path")
    def resourceSize       = column[Long]("resource_size")

    def mediaType          = column[String]("media_type")
    def mediaFps           = column[Float]("media_fps")
    def mediaDuration      = column[Long]("media_duration")
    def mediaWidth         = column[Int]("media_width")
    def mediaHeight        = column[Int]("media_height")

    def title              = column[Option[String]]("title")
    def comment            = column[Option[String]]("comment")
    def tags               = column[Option[String]]("tags")

    def * = (mediaId, uploader, uploadTimestamp, thumbnailTimestamp,
      resourceBucketId, resourceHash, resourcePath, resourceSize,
      mediaFps, mediaDuration, mediaType, mediaWidth, mediaHeight,
      title, comment, tags)
  }

  private val db = dbConfig.db
  private val mediaTable = TableQuery[MediaTable]
  import scala.concurrent.ExecutionContext.Implicits.global

  def createTables(): Future[Unit] = db.run(mediaTable.schema.createIfNotExists)

  def upsert(media: Media): Future[Media] = {
    val query = mediaTable.insertOrUpdate(asRow(media))
    db.run(query).map(_ => media)
  }

  def deleteById(mediaId: String): Future[Int] = {
    val query = mediaTable.filter(_.mediaId === mediaId).delete
    db.run(query)
  }

  def getById(mediaId: String): Future[Option[Media]] = {
    val query = mediaTable.filter(_.mediaId === mediaId).result.headOption.map(_.map(fromRow))
    db.run(query)
  }

  def getAll(limit: Option[Long] = None): Future[Seq[Media]] = {
    val query = limit match {
      case None    => mediaTable
      case Some(n) => mediaTable.take(n)
    }

    db.run(query.result.map(_.map(MediaStorage.fromRow)))
  }
}