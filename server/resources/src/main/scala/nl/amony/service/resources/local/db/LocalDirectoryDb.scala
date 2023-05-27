package nl.amony.service.resources.local.db

import cats.effect.IO
import nl.amony.service.resources.api.Resource
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.util.Try

class LocalDirectoryDb[P <: JdbcProfile](private val dbConfig: DatabaseConfig[P]) extends Logging {

  import cats.effect.unsafe.implicits.global
  import dbConfig.profile.api._
  implicit val ec = scala.concurrent.ExecutionContext.global

  private def dbIO[T](a: DBIO[T]): IO[T] = IO.fromFuture(IO(db.run(a))).onError { t => IO { logger.warn(t) } }

  private val localFilesTable = new LocalFilesTable[P](dbConfig)
  private val tagsTable = new ResourceTagsTable[P](dbConfig)
  private val db = dbConfig.db

  def createTablesIfNotExists(): Unit =
    Try {
      dbIO(
        for {
          _ <- localFilesTable.createIfNotExists
          _ <- tagsTable.createIfNotExists
        } yield ()
      ).unsafeRunSync()
    }

  def getAll(bucketId: String): IO[Seq[Resource]] = {
    dbIO(localFilesTable.allForBucket(bucketId).result).map(_.map(_.toResource(Seq.empty)).toSeq)
  }

  def deleteByRelativePath(bucketId: String, relativePath: String): IO[Int] = {
    dbIO(localFilesTable.queryByPath(bucketId, relativePath).delete)
  }

  def insert(resource: Resource, effect: => IO[Unit]): IO[Unit] = {
    dbIO(
      (for {
        _ <- localFilesTable.insertOrUpdate(resource)
        _ <- tagsTable.insert(resource.hash, resource.tags)
        _ <- DBIO.from(effect.unsafeToFuture())
      } yield ()).transactionally
    )
  }

  def move(bucketId: String, oldPath: String, resource: Resource): IO[Unit] = {
    val transaction = (for {
      _ <- localFilesTable.insertOrUpdate(resource)
      _ <- tagsTable.insert(resource.hash, resource.tags)
      _ <- localFilesTable.queryByPath(bucketId, oldPath).delete
    } yield ()).transactionally

    dbIO(transaction)
  }

  def getByHash(bucketId: String, hash: String): IO[Option[Resource]] = {

    val q = for {
      resourceRow  <- localFilesTable.queryByHash(bucketId, hash).take(1).result.headOption
      resourceTags <- tagsTable.getTags(hash).result
    } yield {
      resourceRow.map { r => r.toResource(resourceTags) }
    }

    dbIO(q)
  }

  // deletes all files with the given hash
  def deleteByHash(bucketId: String, hash: String): IO[Int] =
    dbIO(localFilesTable.queryByHash(bucketId, hash).delete)

}
