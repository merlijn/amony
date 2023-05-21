package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import fs2.Stream
import nl.amony.lib.eventbus.EventTopic
import nl.amony.service.resources.api.Resource
import nl.amony.service.resources.api.events._

import scala.concurrent.duration.DurationInt
import scala.util.Try

class LocalDirectoryStorage[P <: JdbcProfile](
     config: LocalDirectoryConfig,
     topic: EventTopic[ResourceEvent],
     private val dbConfig: DatabaseConfig[P]) extends Logging {

  import dbConfig.profile.api._
  import cats.effect.unsafe.implicits.global
  implicit val ec = scala.concurrent.ExecutionContext.global

  private def dbIO[T](a: DBIO[T]): IO[T] =
    IO.fromFuture(IO(db.run(a))).onError { t => IO { logger.warn(t) } }

  case class LocalFileRow(directoryId: String, relativePath: String, hash: String, size: Long, contentType: Option[String], creationTime: Option[Long], lastModifiedTime: Option[Long]) {

    def toResource(tags: Seq[String]): Resource = {
      Resource(
        path = relativePath,
        hash = hash,
        size = size,
        contentType = contentType,
        tags = tags,
        bucketId = directoryId,
        creationTime = creationTime,
        lastModifiedTime = lastModifiedTime
      )
    }
  }

  private class LocalFiles(ttag: Tag) extends Table[LocalFileRow](ttag, "files") {

    def directoryId = column[String]("directory_id")
    def relativePath = column[String]("relative_path")
    def contentType = column[Option[String]]("content_type")
    def hash = column[String]("hash")
    def size = column[Long]("size")
    def creationTime = column[Option[Long]]("creation_time")

    // we only store this to later check if the file has not been modified
    def lastModifiedTime = column[Option[Long]]("last_modified_time")

    def hashIdx = index("hash_idx", hash)
    def pk = primaryKey("resources_pk", (directoryId, relativePath))

    def * = (directoryId, relativePath, hash, size, contentType, creationTime, lastModifiedTime) <> ((LocalFileRow.apply _).tupled, LocalFileRow.unapply)
  }

  private class ResourceTags(ttag: Tag) extends Table[(String, String)](ttag, "resource_tags") {

    def resourceId = column[String]("resource_id")
    def tag = column[String]("tag")

    def pk = primaryKey("resource_tags_pk", (resourceId, tag))

    def * = (resourceId, tag)
  }

  case class OperationRow(directoryId: String, operation: Array[Byte], operationId: String, output: String)

  private class Operations(ttag: Tag) extends Table[OperationRow](ttag, "operations") {

      def directoryId = column[String]("input")
      def operation = column[Array[Byte]]("operation")
      def operationId: Rep[String] = column[String]("operation_id")
      def output = column[String]("output")

      def pk = primaryKey("operations_pk", (directoryId, operation))

      def * = (directoryId, operation, operationId, output) <> ((OperationRow.apply _).tupled, OperationRow.unapply)
  }

  private val files = TableQuery[LocalFiles]
  private val tags = TableQuery[ResourceTags]

  val db = dbConfig.db

  def createTablesIfNotExists(): Unit =
    Try { dbIO(files.schema.createIfNotExists).unsafeRunSync() }

  logger.info(s"Scanning directory: ${config.resourcePath}")

  Stream
    .fixedDelay[IO](5.seconds)
    .evalMap(_ => IO(scanDirectory()))
    .compile.drain.unsafeRunAndForget()

  private def toResourceRow(resource: Resource): LocalFileRow = LocalFileRow(
    directoryId = config.id,
    relativePath = resource.path,
    hash = resource.hash,
    size = resource.size,
    contentType = resource.contentType,
    creationTime = resource.creationTime,
    lastModifiedTime = resource.lastModifiedTime
  )

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

    def insert(resource: Resource) = {

      val resourceRow  = toResourceRow(resource)
      val tagsToInsert = resource.tags.map((resourceRow.hash, _))

      (for {
        _ <- files.insertOrUpdate(resourceRow)
        _ <- tags ++= tagsToInsert
      } yield ()).transactionally
    }

    def getByPath(relativePath: String) =
      files
        .filter(_.directoryId === config.id)
        .filter(_.relativePath === relativePath).take(1).result
  }

  def scanDirectory(): Unit =
    LocalDirectoryScanner.diff(config, getAll().unsafeRunSync()).foreach {
      case e @ ResourceAdded(resource)               =>
        logger.info(s"File added: ${resource.path}")
        dbIO(files.insertOrUpdate(toResourceRow(resource))).unsafeRunSync()
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

  def getAll(): IO[Seq[Resource]] = {
    dbIO(files.result).map(_.map(_.toResource(Seq.empty)).toSeq)
  }

  def getByHash(hash: String): IO[Option[Resource]] = {

    val q = for {
      resourceRow  <- files.filter(_.hash === hash).take(1).result.headOption
      resourceTags <- tags.filter(_.resourceId === hash).map(_.tag).result
    } yield {
      resourceRow.map { r => r.toResource(resourceTags) }
    }

    dbIO(q)
  }

  // deletes all files with the given hash
  def deleteByHash(hash: String): IO[Int] =
    dbIO(queries.deleteByHash(hash))

}
