package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import nl.amony.service.resources.local.DirectoryScanner._
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import fs2.Stream

import scala.concurrent.duration.DurationInt

class LocalResourcesStoreNew[P <: JdbcProfile](
   config: LocalResourcesConfig,
//   topic: EventTopic[ResourceEvent],
   private val dbConfig: DatabaseConfig[P]) extends Logging {

  import dbConfig.profile.api._
  import cats.effect.unsafe.implicits.global
  implicit val ec = scala.concurrent.ExecutionContext.global

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
    def pk = primaryKey("resources_pk", (directoryId, relativePath))

    def * = (directoryId, relativePath, hash, size, creationTime, lastModifiedTime) <> (LocalFile.tupled, LocalFile.unapply)
  }

  private val files = TableQuery[LocalFiles]
  val db = dbConfig.db

  dbIO(files.schema.createIfNotExists).unsafeRunSync()

  Stream
    .fixedDelay[IO](5.seconds)
    .evalMap(_ => IO(sync()))
    .compile.drain.unsafeRunAndForget()

  private object queries {

    def deleteByHash(hash: String) =
      files
        .filter(_.hash === hash)
        .delete

    def delete(relativePath: String) = files
      .filter(_.directoryId === config.id)
      .filter(_.relativePath === relativePath)
      .delete

    def insert(resource: LocalFile) =
      files.insertOrUpdate(resource)

    def getByPath(relativePath: String) =
      files
        .filter(_.directoryId === config.id)
        .filter(_.relativePath === relativePath).take(1).result
  }

  def sync(): Unit =
    DirectoryScanner.diff(config, getAll().unsafeRunSync()).map {
      case ResourceAdded(resource)               =>
        logger.info(s"File added: ${resource.relativePath}")
        dbIO(files.insertOrUpdate(resource)).unsafeRunSync()
      case ResourceDeleted(_, hash, relativePath)   =>
        logger.info(s"File deleted: ${relativePath}")
        dbIO(queries.delete(relativePath)).unsafeRunSync()
      case ResourceMoved(_, hash, oldPath, newPath) =>
        logger.info(s"File moved: ${oldPath} -> ${newPath}")
        val transaction = (for {
          original <- queries.getByPath(oldPath)
          _        <- queries.insert(original.head.copy(relativePath = newPath))
          _        <- queries.delete(oldPath)
        } yield ()).transactionally

        dbIO(transaction).unsafeRunSync()
    }

  def getAll(): IO[Set[LocalFile]] =
    dbIO(files.result).map(_.toSet)

  def getByHash(hash: String): IO[Option[LocalFile]] =
    dbIO(files.filter(_.hash === hash).take(1).result.headOption)

  // deletes all files with the given hash
  def deleteByHash(hash: String): IO[Int] =
    dbIO(queries.deleteByHash(hash))

}
