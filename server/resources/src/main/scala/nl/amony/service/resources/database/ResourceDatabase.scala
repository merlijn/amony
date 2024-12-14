package nl.amony.service.resources.database

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.events.*
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext

object ResourceDatabase {
    def apply[P <: JdbcProfile](dbConfig: DatabaseConfig[P])(using IORuntime: IORuntime): ResourceDatabase[P] = {
      new ResourceDatabase(dbConfig)
    }
}

class ResourceDatabase[P <: JdbcProfile](private val dbConfig: DatabaseConfig[P])(using IORuntime: IORuntime) extends Logging {

  import dbConfig.profile.api.*
  private val db = dbConfig.db

  given ec: ExecutionContext = db.executor.executionContext
  
  private def dbIO[T](a: DBIO[T]): IO[T] = IO.fromFuture(IO(db.run(a))).onError { t => IO { logger.warn(t) } }

  private val resourcesTable = new ResourceTable[P](dbConfig)
  private val tagsTable = new ResourceTagsTable[P](dbConfig)

  private object queries {
    
    def bucketCount(bucketId: String) = resourcesTable.allForBucket(bucketId).length.result
    
    def joinResourceWithTags(bucketId: String) =
      resourcesTable.innerTable.joinLeft(tagsTable.innerTable)
        .on((a, b) => a.bucketId === b.bucketId && a.resourceId === b.resourceId)
        .filter((resource, maybeTag) => resource.bucketId === bucketId)

    def getWithTags(bucketId: String, filter: Option[resourcesTable.LocalFilesSchema => Rep[Boolean]] = None) = {
      val query = filter match {
        case Some(f) => joinResourceWithTags(bucketId).filter((resource, tag) => f(resource))
        case None    => joinResourceWithTags(bucketId)
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
  }

  def createTablesIfNotExists(): IO[Unit] =
      dbIO(
        for {
          _ <- resourcesTable.createIfNotExists
          _ <- tagsTable.createIfNotExists
        } yield ()
      )

  def count(bucketId: String): IO[Int] =
    dbIO(queries.bucketCount(bucketId))

  def getAll(bucketId: String): IO[Seq[ResourceInfo]] =
    dbIO(queries.getWithTags(bucketId))

  def deleteByRelativePath(bucketId: String, relativePath: String): IO[Int] = 
    dbIO(resourcesTable.getByPath(bucketId, relativePath).delete)

  def update(info: ResourceInfo): IO[Int] = 
    dbIO(resourcesTable.update(info))

  def insert(resource: ResourceInfo, effect: () => IO[Unit] = () => IO.unit): IO[Unit] =
    dbIO(
      (for {
        _ <- resourcesTable.insert(resource)
        _ <- tagsTable.insert(resource.bucketId, resource.hash, resource.tags.toSet)
        _ <- DBIO.from(effect().unsafeToFuture())
      } yield ()).transactionally
    )

  def upsert(resource: ResourceInfo, effect: () => IO[Unit] = () => IO.unit): IO[Unit] =
    dbIO(
      (for {
        _ <- resourcesTable.insertOrUpdate(resource)
        _ <- tagsTable.insertOrUpdate(resource.bucketId, resource.hash, resource.tags.toSet)
        _ <- DBIO.from(effect().unsafeToFuture())
      } yield ()).transactionally
    )

  private def move(bucketId: String, oldPath: String, newPath: String, effect: () => IO[Unit] = () => IO.unit): IO[Unit] =
    dbIO(
      (for {
        old <- resourcesTable.getByPath(bucketId, oldPath).result.head
        _   <- resourcesTable.update(old.copy(relativePath = newPath))
        _   <- DBIO.from(effect().unsafeToFuture())
      } yield ()).transactionally
    )

  def deleteResource(bucketId: String, resourceId: String, effect: () => IO[Unit] = () => IO.unit) : IO[Unit] = {
    val transaction =
      (for {
        _ <- resourcesTable.getByHash(bucketId, resourceId).delete
        _ <- tagsTable.queryById(bucketId, resourceId).delete
        _ <- DBIO.from(effect().unsafeToFuture())
      } yield ()).transactionally

    dbIO(transaction)
  }

  def getAllByIds(bucketId: String, resourceIds: Seq[String]): IO[Seq[ResourceInfo]] =
    dbIO(queries.getWithTags(bucketId, Some(_.resourceId.inSet(resourceIds))))

  def updateThumbnailTimestamp(bucketId: String, resourceId: String, timestamp: Long, effect: ResourceInfo => IO[Unit]): IO[Unit] = {
    val q = (
      for {
        resourceRow <- resourcesTable.getByHash(bucketId, resourceId).result
        updated <- resourceRow.headOption.map { row =>
          val updatedRow = row.copy(thumbnailTimestamp = Some(timestamp))
          resourcesTable.update(updatedRow).map(_ => Some(updatedRow))
        }.getOrElse(DBIO.successful(Option.empty[ResourceRow]))
        tags <- tagsTable.getTags(bucketId, resourceId).result
        _ <- updated.map(row => DBIO.from( effect(row.toResource(tags)).unsafeToFuture() )).getOrElse(DBIO.successful(()))
      } yield ()).transactionally
    
    dbIO(q).map(_ => ())
  }
  
  def updateUserMeta(bucketId: String, hash: String, title: Option[String], description: Option[String], tags: List[String], effect: ResourceInfo => IO[Unit]): IO[Unit] = {
    val q = (for {
      resourceRow <- resourcesTable.getByHash(bucketId, hash).result
          updated <- resourceRow.headOption.map { row =>
                       val updatedRow = row.copy(title = title, description = description)
                       resourcesTable.update(updatedRow).map(_ => Some(updatedRow))
                     }.getOrElse(DBIO.successful(Option.empty[ResourceRow]))
                _ <- tagsTable.insertOrUpdate(bucketId, hash, tags.toSet)
                _ <- updated.map(row => DBIO.from( effect(row.toResource(tags)).unsafeToFuture() )).getOrElse(DBIO.successful(()))
    } yield ()).transactionally

    dbIO(q)
  }

  def getByHash(bucketId: String, hash: String): IO[Option[ResourceInfo]] = {

    val q = for {
      resourceRow  <- resourcesTable.getByHash(bucketId, hash).take(1).result.headOption
      resourceTags <- tagsTable.getTags(bucketId, hash).result
    } yield {
      resourceRow.map { r => r.toResource(resourceTags) }
    }

    dbIO(q)
  }
  
  def applyEvent(bucketId: String, effect: ResourceEvent => IO[Unit])(event: ResourceEvent): IO[Unit] = {
    event match {
      case ResourceAdded(resource)             => insert(resource, () => effect(event))
      case ResourceDeleted(resourceId)         => deleteResource(bucketId, resourceId, () => effect(event))
      case ResourceMoved(id, oldPath, newPath) => move(bucketId, oldPath, newPath, () => effect(event))
      case _ => IO.unit
    }
  }
}
