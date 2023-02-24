package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.lib.eventstore.EventTopic
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import nl.amony.service.resources.local.DirectoryScanner._
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

class LocalResourcesStoreNew[P <: JdbcProfile](
   config: LocalResourcesConfig,
   topic: EventTopic[ResourceEvent],
   private val dbConfig: DatabaseConfig[P]) extends Logging {

  import dbConfig.profile.api._
  import cats.effect.unsafe.implicits.global


  def dbIO[T](a: DBIO[T]): IO[T] =
    IO.fromFuture(IO(db.run(a))).onError { t => IO { logger.warn(t) } }

  private class LocalFiles(tag: Tag) extends Table[LocalFile](tag, "files") {

    def directoryId = column[String]("directory_id")
    def relativePath = column[String]("relative_path")
    def hash = column[String]("hash")
    def size = column[Long]("size")
    def creationTime = column[Long]("creation_time")
    def lastModifiedTime = column[Long]("last_modified_time")

    def hashIdx = index("hash_idx", hash)
    def pk = primaryKey("files_pk", (directoryId, relativePath))

    def * = (relativePath, hash, size, creationTime, lastModifiedTime) <> (LocalFile.tupled, LocalFile.unapply)
  }

  private val files = TableQuery[LocalFiles]
  val db = dbConfig.db

  def sync() =
    DirectoryScanner.diff(config, getAll().unsafeRunSync()).foreach {
      case ResourceAdded(resource)               =>
        files.insertOrUpdate(resource)
      case ResourceDeleted(hash, relativePath)   =>
        files
          .filter(_.directoryId == config.id)
          .filter(_.relativePath == relativePath)
          .delete
      case ResourceMoved(hash, oldPath, newPath) =>
    }


  def getAll(): IO[Set[LocalFile]] =
    dbIO(files.result).map(_.toSet)

  def getByHash(hash: String): IO[Option[LocalFile]] =
    dbIO(files.filter(_.hash === hash).take(1).result.headOption)



}
