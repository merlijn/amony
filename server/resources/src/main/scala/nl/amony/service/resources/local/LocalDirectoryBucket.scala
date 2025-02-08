package nl.amony.service.resources.local

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Pipe
import nl.amony.lib.eventbus.EventTopic
import nl.amony.lib.files.*
import nl.amony.service.resources.*
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta}
import nl.amony.service.resources.api.events.{ResourceDeleted, ResourceEvent, ResourceUpdated}
import nl.amony.service.resources.api.operations.ResourceOperation
import nl.amony.service.resources.database.ResourceDatabase
import nl.amony.service.resources.local.LocalResourceOperations.*
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap

object LocalDirectoryBucket:
  
  def resource(config: LocalDirectoryConfig, db: ResourceDatabase, topic: EventTopic[ResourceEvent])(using runtime: IORuntime): cats.effect.Resource[IO, LocalDirectoryBucket] = {
    cats.effect.Resource.make {
      IO {
        val bucket = LocalDirectoryBucket(config, db, topic)
        bucket.sync().unsafeRunAsync(_ => ())
        bucket
      }
    }{  _ => IO.unit }
  }

class LocalDirectoryBucket(config: LocalDirectoryConfig, db: ResourceDatabase, topic: EventTopic[ResourceEvent])(using runtime: IORuntime) extends ResourceBucket with Logging {

  private val runningOperations = new ConcurrentHashMap[LocalResourceOp, IO[Path]]()
  
  Files.createDirectories(config.cachePath)

  private def getResourceInfo(resourceId: String): IO[Option[ResourceInfo]] = 
    db.getById(config.id, resourceId).map(_.map(recoverMeta))

  private def recoverMeta(info: ResourceInfo) = {
    val meta: Option[ResourceMeta] = info.contentMetaSource.flatMap(meta => LocalResourceMeta.scanToolMeta(meta).toOption)
    info.copy(contentMeta = meta.getOrElse(ResourceMeta.Empty))
  }

  override def id = config.id

  def reScanAllMetadata(): IO[Unit] =
    getAllResources().evalMap(resource => {
        val f = config.resourcePath.resolve(resource.path)
        LocalResourceMeta.detectMetaData(f).flatMap:
          case None => 
            logger.warn(s"Failed to scan metadata for $f")
            IO.unit
          case Some(localResourceMeta) =>
            
            logger.info(s"Updating metadata for $f")
            val updated = resource.copy(
              contentType       = Some(localResourceMeta.contentType), 
              contentMetaSource = localResourceMeta.toolMeta,
              contentMeta       = localResourceMeta.meta)
            
            IO.unit
//            db.update(updated) >> topic.publish(ResourceUpdated(updated))

    }).compile.drain

  def reComputeHashes(): IO[Unit] =
    getAllResources().evalMap(resource => {
      val file = config.resourcePath.resolve(resource.path)
      config.scan.hashingAlgorithm.createHash(file).flatMap:
        hash =>
          val oldResourceId = resource.resourceId
          val updated = resource.copy(resourceId = hash, hash = Some(hash))
          if (oldResourceId != hash)
            logger.info(s"Updating hash for $file from $oldResourceId to $hash")
            db.deleteResource(config.id, resource.resourceId) >> topic.publish(ResourceDeleted(oldResourceId)) >> 
              db.insertResource(updated) >> topic.publish(ResourceUpdated(updated))
          else
            IO.unit

    }).compile.drain

  override def getOrCreate(resourceId: String, operation: ResourceOperation): IO[Option[Resource]] =
    getResourceInfo(resourceId).flatMap:
      case None           => IO.pure(None)
      case Some(fileInfo) => derivedResource(fileInfo, LocalResourceOp(resourceId, operation))

  private def derivedResource(inputResource: ResourceInfo, operation: LocalResourceOp): IO[Option[LocalFile]] = {
    
    val outputFile = config.cachePath.resolve(operation.outputFilename)
    
    val derivedResourceInfo = inputResource.copy(
      path = outputFile.getFileName.toString,
      contentType = Some(operation.contentType),
      contentMetaSource = None,
      contentMeta = ResourceMeta.Empty
    )

    if (Files.exists(outputFile))
      IO.pure(Resource.fromPathMaybe(outputFile, derivedResourceInfo))
    else {
      /**
       * This is not ideal, there is still a small time window in which the operation can be triggered multiple times, although it is very unlikely to happen
       * TODO Create a full proof solution using a MapRef from cats
       */
      runningOperations
        .compute(operation, (_, value) => { createResource(config.resourcePath.resolve(inputResource.path), inputResource, config.cachePath, operation) })
        .map(path => Resource.fromPathMaybe(path, derivedResourceInfo))
        .flatTap { _ => IO(runningOperations.remove(operation)) }
    }
  }

  override def getResource(resourceId: String): IO[Option[Resource]] =
    getResourceInfo(resourceId).flatMap:
      case None       =>
        IO.pure(None)
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        if(!path.exists()) {
          logger.warn(s"Resource '$resourceId' was found in the database but no file exists: $path")
          IO.pure(None)
        } else {
          IO.pure(Some(Resource.fromPath(path, info)))
        }

  override def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[ResourceInfo] = ???

  override def deleteResource(resourceId: String): IO[Unit] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(())
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        db.deleteResource(config.id, resourceId) >> IO(path.deleteIfExists())

  override def updateUserMeta(resourceId: String, title: Option[String], description: Option[String], tags: List[String]): IO[Unit] = {
    db.updateUserMeta(config.id, resourceId, title, description, tags).flatMap(
      _.map(updated => topic.publish(ResourceUpdated(recoverMeta(updated)))).getOrElse(IO.unit)
    )
  }

  override def updateThumbnailTimestamp(resourceId: String, timestamp: Long): IO[Unit] =
    db.updateThumbnailTimestamp(config.id, resourceId, timestamp).flatMap(
      _.map(updated => topic.publish(ResourceUpdated(recoverMeta(updated)))).getOrElse(IO.unit)
    )

  val updateDb: Pipe[IO, ResourceEvent, ResourceEvent] = _ evalTap (db.applyEvent(config.id, e => topic.publish(e)))
  val logEvent: Pipe[IO, ResourceEvent, ResourceEvent] = _ evalTap (e => IO(logger.info(s"Resource event: $e")))

  def refresh(): IO[Unit] =
    db.getAll(config.id).map(_.toSet).flatMap: allResources =>
      LocalResourceScanner.singleScan(allResources, config)
          .through(logEvent)
          .through(updateDb)
          .compile
          .drain

  def sync(): IO[Unit] = {

    if (!config.scan.enabled)
      IO.unit
    else {
      logger.info(s"Starting sync for directory: ${config.resourcePath.toAbsolutePath}")

      def stateFromStorage(): Set[ResourceInfo] = db.getAll(config.id).map(_.toSet).unsafeRunSync()

      def pollRetry(s: Set[ResourceInfo]): fs2.Stream[IO, ResourceEvent] =
        LocalResourceScanner.pollingResourceEventStream(stateFromStorage(), config).handleErrorWith { e =>
          logger.error(s"Scanner failed for ${config.resourcePath.toAbsolutePath}, retrying in ${config.scan.pollInterval}", e)
          fs2.Stream.sleep[IO](config.scan.pollInterval) >> pollRetry(stateFromStorage())
        }

      pollRetry(stateFromStorage())
        .through(logEvent)
        .through(updateDb)
        .compile
        .drain
    }
  }

  override def getAllResources(): fs2.Stream[IO, ResourceInfo] =
    // TODO this should be a stream from the database
    fs2.Stream.eval(db.getAll(config.id)).flatMap { resources => fs2.Stream.emits[IO, ResourceInfo](resources.map(recoverMeta)) }
}
