package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import fs2.Stream
import nl.amony.lib.eventbus.EventTopic
import nl.amony.service.resources.events.{Resource, ResourceAdded, ResourceDeleted, ResourceEvent, ResourceMoved}

import scala.concurrent.duration.DurationInt
import scala.util.Try

class LocalDirectoryStorage[P <: JdbcProfile](
   config: LocalResourcesConfig,
   topic: EventTopic[ResourceEvent],
   private val dbConfig: DatabaseConfig[P]) extends Logging {

  import dbConfig.profile.api._
  import cats.effect.unsafe.implicits.global
  implicit val ec = scala.concurrent.ExecutionContext.global

  private def dbIO[T](a: DBIO[T]): IO[T] =
    IO.fromFuture(IO(db.run(a))).onError { t => IO { logger.warn(t) } }

  private class LocalFiles(tag: Tag) extends Table[Resource](tag, "files") {

    def directoryId = column[String]("directory_id")
    def relativePath = column[String]("relative_path")
    def contentType = column[String]("content_type")
    def hash = column[String]("hash")
    def size = column[Long]("size")
    def creationTime = column[Option[Long]]("creation_time")
    def lastModifiedTime = column[Option[Long]]("last_modified_time")

    def hashIdx = index("hash_idx", hash)
    def pk = primaryKey("resources_pk", (directoryId, relativePath))

    def * = (directoryId, relativePath, hash, contentType, size, creationTime, lastModifiedTime) <> ((Resource.apply _).tupled, Resource.unapply)
  }

  private val files = TableQuery[LocalFiles]
  val db = dbConfig.db

  Try { dbIO(files.schema.createIfNotExists).unsafeRunSync() }

  logger.info(s"Scanning directory: ${config.mediaPath}")

  Stream
    .fixedDelay[IO](5.seconds)
    .evalMap(_ => IO(scanDirectory()))
    .compile.drain.unsafeRunAndForget()

  private object queries {

    def deleteByHash(hash: String) =
      files
        .filter(_.hash === hash)
        .delete

    def delete(relativePath: String) =
      files
        .filter(_.directoryId === config.id)
        .filter(_.relativePath === relativePath)
        .delete

    def insert(resource: Resource) =
      files.insertOrUpdate(resource)

    def getByPath(relativePath: String) =
      files
        .filter(_.directoryId === config.id)
        .filter(_.relativePath === relativePath).take(1).result
  }

  def scanDirectory(): Unit =
    LocalDirectoryScanner.diff(config, getAll().unsafeRunSync()).map {
      case e @ ResourceAdded(resource)               =>
        logger.info(s"File added: ${resource.path}")
        dbIO(files.insertOrUpdate(resource)).unsafeRunSync()
        topic.publish(e)
      case e @ ResourceDeleted(resource)   =>
        logger.info(s"File deleted: ${resource.path}")
        dbIO(queries.delete(resource.path)).unsafeRunSync()
        topic.publish(e)
      case e @ ResourceMoved(resource, oldPath) =>
        logger.info(s"File moved: ${oldPath} -> ${resource.path}")
        val transaction = (for {
          _        <- queries.insert(resource)
          _        <- queries.delete(oldPath)
        } yield ()).transactionally

        dbIO(transaction).unsafeRunSync()
        topic.publish(e)
    }

  def getAll(): IO[Set[Resource]] =
    dbIO(files.result).map(_.toSet)

  def getByHash(hash: String): IO[Option[Resource]] =
    dbIO(files.filter(_.hash === hash).take(1).result.headOption)

  // deletes all files with the given hash
  def deleteByHash(hash: String): IO[Int] =
    dbIO(queries.deleteByHash(hash))

}
