package nl.amony.service.resources.local.db

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.operations.ResourceOperation
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import nl.amony.service.resources.api.events.*
import slick.lifted.LiteralColumn

import scala.concurrent.ExecutionContextExecutor
import scala.util.Try

class LocalDirectoryDb[P <: JdbcProfile](private val dbConfig: DatabaseConfig[P])(implicit IORuntime: IORuntime) extends Logging {

  import dbConfig.profile.api._
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  private val db = dbConfig.db

  private def dbIO[T](a: DBIO[T]): IO[T] = IO.fromFuture(IO(db.run(a))).onError { t => IO { logger.warn(t) } }

  private val resourcesTable = new LocalFilesTable[P](dbConfig)
  private val tagsTable = new ResourceTagsTable[P](dbConfig)

  private object queries {
    def joinResourceWithTags(bucketId: String) =
      resourcesTable.innerTable.joinLeft(tagsTable.innerTable)
        .on((a, b) => a.bucketId === b.bucketId && a.resourceId === b.resourceId)
        .filter((resource, maybeTag) => resource.bucketId === bucketId)

    def getWithTags(bucketId: String, filter: Option[resourcesTable.LocalFilesSchema => Rep[Boolean]] = None) = {
      val query = filter match {
        case Some(f) => joinResourceWithTags(bucketId).filter((resource, tag) => f(resource))
        case None => joinResourceWithTags(bucketId)
      }

      query.result
        .map {
          _.groupBy(_._1.hash).values.map {
            rows =>
              val tags = rows.map((_, maybeTag) => maybeTag.map(_.tag)).flatten
              val resource = rows.head._1 // this is safe since we know there is at least one row
              resource.toResource(tags)
          }.toSeq
        }
    }

    def insertOrUpdate(resource: ResourceInfo) = {
      resourcesTable.insertOrUpdate(resource)
    }
  }

  def createTablesIfNotExists(): Unit =
    Try {
      dbIO(
        for {
          _ <- resourcesTable.createIfNotExists
          _ <- tagsTable.createIfNotExists
        } yield ()
      ).unsafeRunSync()
    }

  def getAll(bucketId: String): IO[Seq[ResourceInfo]] =
    dbIO(queries.getWithTags(bucketId))

  def deleteByRelativePath(bucketId: String, relativePath: String): IO[Int] = 
    dbIO(resourcesTable.queryByPath(bucketId, relativePath).delete)

  def update(bucketId: String, resourceId: String)(fn: Option[ResourceInfo] => ResourceInfo): IO[Unit] = 
    dbIO(
      (for {
        resource <- queries.getWithTags(bucketId, Some(_.resourceId === resourceId))
        _        <- resource.headOption.map { row => resourcesTable.update(fn(Some(row))) }.getOrElse(DBIO.successful(0))
      } yield ()).transactionally
    )

  def insert(resource: ResourceInfo, effect: => IO[Unit]): IO[Unit] =
    dbIO(
      (for {
        _ <- resourcesTable.insert(resource)
        _ <- tagsTable.insert(resource.bucketId, resource.hash, resource.tags.toSet)
        _ <- DBIO.from(effect.unsafeToFuture())
      } yield ()).transactionally
    )

  def upsert(resource: ResourceInfo, effect: => IO[Unit]): IO[Unit] =
    dbIO(
      (for {
        _ <- resourcesTable.insertOrUpdate(resource)
        _ <- tagsTable.insertOrUpdate(resource.bucketId, resource.hash, resource.tags.toSet)
        _ <- DBIO.from(effect.unsafeToFuture())
      } yield ()).transactionally
    )

  private def move(bucketId: String, oldPath: String, resource: ResourceInfo): IO[Unit] =
    dbIO(
      (for {
        old <- resourcesTable.queryByPath(bucketId, oldPath).result.head
        _   <- resourcesTable.update(old.copy(relativePath = resource.path))
      } yield ()).transactionally
    )

  def deleteResource(bucketId: String, resourceId: String): IO[Unit] = {
    val transaction =
      (for {
        _ <- resourcesTable.queryByHash(bucketId, resourceId).delete
        _ <- tagsTable.queryById(bucketId, resourceId).delete
      } yield ()).transactionally

    dbIO(transaction)
  }

  def getAllByIds(bucketId: String, resourceIds: Seq[String]): IO[Seq[ResourceInfo]] =
    dbIO(queries.getWithTags(bucketId, Some(_.resourceId.inSet(resourceIds))))

  def getChildren(bucketId: String, parentId: String, tags: Set[String]): IO[Seq[(ResourceInfo)]] =
    def resourceIdsForTag = queries.joinResourceWithTags(bucketId)
      .filter((resource, maybeTag) => resource.parentId === parentId)
      .filter((resource, maybeTag) => maybeTag.fold(LiteralColumn(true))(_.tag.inSet(tags)))
      .distinct.map(_._1.resourceId).result

    dbIO(resourceIdsForTag).flatMap {
      resourceIds => getAllByIds(bucketId, resourceIds)
    }

  def insertChildResource(parentId: String, operation: ResourceOperation, resource: ResourceInfo) = {

  }

  def updateUserMeta(bucketId: String, hash: String, title: Option[String], description: Option[String], tags: List[String], effect: IO[Unit]): IO[Unit] = {
    val q = (for {
      resourceRow <- resourcesTable.queryByHash(bucketId, hash).result
                _ <- resourceRow.headOption.map { row =>
                       resourcesTable.update(row.copy(title = title, description = description))
                     }.getOrElse(DBIO.successful(0))
                _ <- tagsTable.insertOrUpdate(bucketId, hash, tags.toSet)
                _ <- DBIO.from(effect.unsafeToFuture())
    } yield ()).transactionally

    dbIO(q)
  }

  def getByHash(bucketId: String, hash: String): IO[Option[ResourceInfo]] = {

    val q = for {
      resourceRow  <- resourcesTable.queryByHash(bucketId, hash).take(1).result.headOption
      resourceTags <- tagsTable.getTags(bucketId, hash).result
    } yield {
      resourceRow.map { r => r.toResource(resourceTags) }
    }

    dbIO(q)
  }
  
  def applyEvent(event: ResourceEvent): IO[Unit] = {
    event match {
      case ResourceAdded(resource)          => insert(resource, IO.unit)
      case ResourceDeleted(resource)        => deleteResource(resource.bucketId, resource.hash)
      case ResourceMoved(resource, oldPath) => move(resource.bucketId, oldPath, resource)
      case _ => IO.unit
    }
  }
}
