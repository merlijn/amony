package nl.amony.service.media

import MediaProtocol.{Media, MediaInfo, MediaMeta, ResourceInfo}
import nl.amony.service.media.MediaRepository.{MediaRow, asRow, fromRow}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.Future

object MediaRepository {

  type MediaRow = (String, String, Long, Long, String, String, Long, Double, Long, Int, Int, Option[String], Option[String])

  def asRow(media: Media) =
    (media.id, media.uploader, media.uploadTimestamp, media.thumbnailTimestamp,
      media.resourceInfo.bucketId, media.resourceInfo.hash, media.resourceInfo.size,
      media.mediaInfo.fps, media.mediaInfo.duration, media.width, media.height,
      media.meta.title, media.meta.comment)

  def fromRow(row: MediaRow): Media = row match {
    case (mediaId, uploader, uploadTimestamp, thumbnailTimestamp,
          resourceBucketId, resourceHash, resourceSize,
          videoFps, videoDuration, mediaWidth, mediaHeight,
          title, comment) =>

      val resourceInfo = ResourceInfo(resourceBucketId, "", resourceHash, resourceSize)
      val mediaInfo = MediaInfo(videoFps, "mp4", videoDuration, (mediaWidth, mediaHeight))
      val meta = MediaMeta(title, comment, Set.empty)

      Media(mediaId, uploader, uploadTimestamp, resourceInfo, mediaInfo, meta, thumbnailTimestamp)
  }
}

class MediaRepository(dbConfig: DatabaseConfig[JdbcProfile]) extends Logging {

  import dbConfig.profile.api._

  private class MediaTable(tag: Tag) extends Table[MediaRow](tag, "media") {

    def mediaId            = column[String]("key", O.PrimaryKey) // This is the primary key column

    def uploader           = column[String]("uploader")
    def uploadTimestamp    = column[Long]("upload_timestamp")
    def thumbnailTimestamp = column[Long]("thumbnail_timestamp")

    def resourceBucketId   = column[String]("resource_bucket_id")
    def resourceHash       = column[String]("resource_hash")
    def resourceSize       = column[Long]("resource_size")

    def mediaFps           = column[Double]("media_fps")
    def mediaDuration      = column[Long]("media_duration")
    def mediaResolutionX   = column[Int]("media_resolution_x")
    def mediaResolutionY   = column[Int]("media_resolution_y")

    def title              = column[Option[String]]("title")
    def comment            = column[Option[String]]("comment")

    def * = (mediaId, uploader, uploadTimestamp, thumbnailTimestamp,
      resourceBucketId, resourceHash, resourceSize,
      mediaFps, mediaDuration, mediaResolutionX, mediaResolutionY,
      title, comment)
  }

  private val db = dbConfig.db
  private val mediaTable = TableQuery[MediaTable]
  import scala.concurrent.ExecutionContext.Implicits.global

  def createTables(): Future[Unit] = db.run(mediaTable.schema.create)

  def upsert(media: Media): Future[Media] = {
    val query = mediaTable += asRow(media)
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

    db.run(query.result.map(_.map(MediaRepository.fromRow)))
  }

  def updateMeta(mediaId: String, meta: MediaMeta) = {

  }
}