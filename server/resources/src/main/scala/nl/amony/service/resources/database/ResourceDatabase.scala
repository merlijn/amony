package nl.amony.service.resources.database

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import nl.amony.service.resources.api.events.*
import nl.amony.service.resources.api.ResourceInfo
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext

object ResourceDatabase {
    def resource[P <: JdbcProfile](dbConfig: DatabaseConfig[P])(using IORuntime: IORuntime): Resource[IO, ResourceDatabase[P]] = {
      Resource.make {
        IO {
          val db = new ResourceDatabase(dbConfig)
          db.init()
          db
        }
      } { db => IO(db.db.close()) }
    }
}

class ResourceDatabase[P <: JdbcProfile](private val dbConfig: DatabaseConfig[P])(using IORuntime: IORuntime) extends Logging {

  import dbConfig.profile.api.*
  private val db: dbConfig.profile.backend.JdbcDatabaseDef = dbConfig.db

  given ec: ExecutionContext = db.executor.executionContext
  
  private def dbIO[T](a: => DBIO[T]): IO[T] = IO.fromFuture(IO(db.run(a))) //.onError { t => IO { logger.warn(t) } }

  private val resources = new ResourceTable[P](dbConfig)
  private val resourceTags = new ResourceTagsTable[P](dbConfig)
  private val tags = new TagsTable[P](dbConfig)

  private object queries {
    
    def bucketCount(bucketId: String) = resources.allForBucket(bucketId).length.result
    
    def joinResourceWithTags(bucketId: String) =
      resources.table.joinLeft(resourceTags.table)
        .on((a, b) => a.bucketId === b.bucketId && a.resourceId === b.resourceId)
        .joinLeft(tags.table).on((a, b) => a._2.map(_.tagId) === b.id)
        .filter {
          case ((resource, maybeTag), _) => resource.bucketId === bucketId
        }.map {
          case ((resource, maybeTag), tags) => (resource, maybeTag, tags)
        }

    def getWithTags(bucketId: String, filter: Option[resources.ResourceSchema => Rep[Boolean]] = None) = {
      val query = filter match {
        case Some(f) => joinResourceWithTags(bucketId).filter((resource, tag, _) => f(resource))
        case None    => joinResourceWithTags(bucketId)
      }

      query.result
        .map {
          _.groupBy(_._1.hash).values.map {
            rows =>
              val tags: Seq[String] = rows.collect { case (_, Some(tagId), Some(tagInfo)) => tagInfo.label }

              val resource = rows.head._1 // this is safe since we know there is at least one row
              resource.toResource(tags.toSet)
          }.toSeq
        }
    }
  }
  
  def init() = {
    val connection = db.source.createConnection()
    val liquibaseDatabase = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
    val liquibase = new Liquibase("db/00-changelog.yaml", new ClassLoaderResourceAccessor(), liquibaseDatabase)
    liquibase.update()
  }
  
  def count(bucketId: String): IO[Int] =
    dbIO(queries.bucketCount(bucketId))

  def getAll(bucketId: String): IO[Seq[ResourceInfo]] =
    dbIO(queries.getWithTags(bucketId))

  def deleteByRelativePath(bucketId: String, relativePath: String): IO[Int] = 
    dbIO(resources.getByPath(bucketId, relativePath).delete)

  def update(info: ResourceInfo): IO[Int] = 
    dbIO(resources.update(info))

  def insert(resource: ResourceInfo, effect: () => IO[Unit] = () => IO.unit): IO[Unit] =
    dbIO(
      (for {
        _      <- resources.insert(resource)
        tagIds <- tags.upsertMissingLabels(resource.tags).map(_.flatMap(_.id).toSeq)
        _      <- resourceTags.insert(resource.bucketId, resource.resourceId, tagIds.toSet)
        _      <- DBIO.from(effect().unsafeToFuture())
      } yield ()).transactionally
    )

  def upsert(resource: ResourceInfo, effect: () => IO[Unit] = () => IO.unit): IO[Unit] =
    dbIO(
      (for {
        _       <- resources.upsert(resource)
        tagIds  <- tags.upsertMissingLabels(resource.tags).map(_.flatMap(_.id).toSeq)
        _       <- resourceTags.upsert(resource.bucketId, resource.resourceId, tagIds.toSet)
        _       <- DBIO.from(effect().unsafeToFuture())
      } yield ()).transactionally
    )

  private def move(bucketId: String, oldPath: String, newPath: String, effect: () => IO[Unit] = () => IO.unit): IO[Unit] =
    dbIO(
      (for {
        old <- resources.getByPath(bucketId, oldPath).result.head
        _   <- resources.update(old.copy(relativePath = newPath))
        _   <- DBIO.from(effect().unsafeToFuture())
      } yield ()).transactionally
    )

  def deleteResource(bucketId: String, resourceId: String, effect: () => IO[Unit] = () => IO.unit) : IO[Unit] = {
    dbIO(
      (for {
        _ <- resources.getById(bucketId, resourceId).delete
        _ <- resourceTags.queryById(bucketId, resourceId).delete
        _ <- DBIO.from(effect().unsafeToFuture())
      } yield ()).transactionally
    )
  }

  def getAllByIds(bucketId: String, resourceIds: Seq[String]): IO[Seq[ResourceInfo]] =
    dbIO(queries.getWithTags(bucketId, Some(_.resourceId.inSet(resourceIds))))
    
  def getAllTagLabels() = 
    dbIO(tags.getAllLabels()).map(_.toSet)

  def updateThumbnailTimestamp(bucketId: String, resourceId: String, timestamp: Long, effect: ResourceInfo => IO[Unit]): IO[Unit] = {
    def q = (
      for {
        resourceRow  <- resources.getById(bucketId, resourceId).result
        updated      <- resourceRow.headOption.map { row =>
          val updatedRow = row.copy(thumbnailTimestamp = Some(timestamp))
          resources.update(updatedRow).map(_ => Some(updatedRow))
        }.getOrElse(DBIO.successful(Option.empty[ResourceRow]))
        tagIds    <- resourceTags.getTags(bucketId, resourceId).result
        tagLabels <- tags.getTagsByIds(tagIds).map(_.map(_.label).toSet)
        _ <- updated.map(row => DBIO.from( effect(row.toResource(tagLabels)).unsafeToFuture() )).getOrElse(DBIO.successful(()))
      } yield ()).transactionally
    
    dbIO(q).map(_ => ())
  }
  
  def updateUserMeta(bucketId: String, resourceId: String, title: Option[String], description: Option[String], tagLabels: List[String], effect: ResourceInfo => IO[Unit]): IO[Unit] = {
    def q = (for {
      resourceRow  <- resources.getById(bucketId, resourceId).result
       updatedRow  <- resourceRow.headOption.map { row =>
                        val updatedRow = row.copy(title = title, description = description)
                        resources.update(updatedRow).map(_ => Some(updatedRow))
                      }.getOrElse(DBIO.successful(Option.empty[ResourceRow]))
          rtags    <- tags.upsertMissingLabels(tagLabels.toSet)
          tagIds    = rtags.flatMap(_.id).toSet
          tagLabels = rtags.map(_.label).toSet
                _   <- resourceTags.upsert(bucketId, resourceId, tagIds)
                _   <- updatedRow.map(row => DBIO.from(effect(row.toResource(tagLabels)).unsafeToFuture() )).getOrElse(DBIO.successful(()))
    } yield ()).transactionally

    dbIO(q)
  }

  def getByResourceId(bucketId: String, resourceId: String): IO[Option[ResourceInfo]] =
    dbIO(queries.getWithTags(bucketId, Some(_.resourceId === resourceId))).map(_.headOption)
  
  def applyEvent(bucketId: String, effect: ResourceEvent => IO[Unit])(event: ResourceEvent): IO[Unit] = {
    event match {
      case ResourceAdded(resource)             => insert(resource, () => effect(event))
      case ResourceDeleted(resourceId)         => deleteResource(bucketId, resourceId, () => effect(event))
      case ResourceMoved(id, oldPath, newPath) => move(bucketId, oldPath, newPath, () => effect(event))
      case _ => IO.unit
    }
  }
}
