package nl.amony.modules.resources

import java.nio.file.Files

import cats.data.EitherT
import cats.effect.std.Supervisor
import cats.effect.{Deferred, Fiber, IO, Ref, Resource}
import cats.implicits.*
import scribe.Logging

import nl.amony.modules.resources.ResourceConfig.{LocalDirectoryConfig, ResourceBucketConfig}
import nl.amony.modules.resources.api.{BucketId, ResourceBucket}
import nl.amony.modules.resources.dal.{BucketsDal, StoredBucket}
import nl.amony.modules.search.api.SearchService

enum BucketError:
  case NotFound(bucketId: BucketId)
  case AlreadyExists(bucketId: BucketId)
  case Modified(bucketId: BucketId)
  case Invalid(message: String)
  case NotEmpty(bucketId: BucketId, resourceCount: Long)

/** Creates a bucket from its configuration, together with its background (sync) process. */
type BucketFactory = ResourceBucketConfig => IO[(ResourceBucket, IO[Unit])]

trait BucketRegistry:
  def get(bucketId: BucketId): IO[Option[ResourceBucket]]
  def all: IO[List[ResourceBucket]]
  def getConfig(bucketId: BucketId): IO[Option[StoredBucket]]
  def allConfigs: IO[List[StoredBucket]]
  def create(config: ResourceBucketConfig): IO[Either[BucketError, Unit]]

  /** Updates a bucket, unless it was changed since `expectedUpdatedAt`. */
  def update(config: ResourceBucketConfig, expectedUpdatedAt: java.time.Instant): IO[Either[BucketError, Unit]]

  /** Deletes a bucket from the database and search index. Media files are never deleted. */
  def delete(bucketId: BucketId, force: Boolean): IO[Either[BucketError, Unit]]

object BucketRegistry extends Logging:

  /** Resolves relative paths against the working directory, so the stored configuration does not depend on it. */
  private def withAbsolutePaths(config: ResourceBucketConfig): ResourceBucketConfig = config match
    case c: LocalDirectoryConfig => c.copy(path = c.path.toAbsolutePath.normalize())

  private def storeAbsolutePaths(bucketsDal: BucketsDal)(stored: StoredBucket): IO[StoredBucket] =
    val absolute = withAbsolutePaths(stored.config)
    if absolute == stored.config then IO.pure(stored)
    else
      bucketsDal.update(absolute, stored.updatedAt).flatMap {
        case Right(updated) => IO(logger.info(s"Stored absolute path for bucket '${absolute.id}'")).as(updated)
        case Left(error)    => IO(logger.warn(s"Could not store absolute path for bucket '${absolute.id}': $error")).as(stored)
      }

  /** Inserts the default bucket when no buckets exist yet, unless its directory does not exist. */
  private def seedDefaultBucket(defaultBucket: ResourceBucketConfig, bucketsDal: BucketsDal): IO[Unit] =
    withAbsolutePaths(defaultBucket) match
      case config: LocalDirectoryConfig =>
        bucketsDal.anyExist().flatMap {
          case true  => IO.unit
          case false =>
            IO.blocking(Files.isDirectory(config.resourcePath)).flatMap {
              case false =>
                IO(logger.error(
                  s"No buckets found, but the directory '${config.resourcePath}' of the default bucket '${config.id}' does not exist. Not inserting it."
                ))
              case true  =>
                bucketsDal.insertIfNoneExist(config).flatMap(inserted =>
                  IO.whenA(inserted)(IO(logger.info(s"No buckets found, inserted default bucket '${config.id}'")))
                )
            }
        }

  private case class RunningBucket(stored: StoredBucket, bucket: ResourceBucket, fiber: Fiber[IO, Throwable, Unit])

  /**
   * Loads the buckets from the database and starts them. When no buckets exist yet, the default bucket is inserted
   * first.
   */
  def resource(
    defaultBucket: ResourceBucketConfig,
    bucketsDal: BucketsDal,
    searchService: SearchService,
    factory: BucketFactory
  ): Resource[IO, BucketRegistry] =
    for
      supervisor <- Supervisor[IO]
      registry   <- Resource.eval {
                      for
                        _       <- seedDefaultBucket(defaultBucket, bucketsDal)
                        running <- Ref.of[IO, Map[BucketId, RunningBucket]](Map.empty)
                        registry = Impl(bucketsDal, searchService, factory, supervisor, running)
                        stored  <- bucketsDal.getAll().flatMap(_.traverse(storeAbsolutePaths(bucketsDal)))
                        _       <- stored.traverse_(registry.install)
                      yield registry
                    }
    yield registry

  /**
   * The database is the source of truth for the bucket configuration. `running` only holds the buckets this instance
   * runs, which is needed to stop or restart their background sync process when a bucket changes.
   */
  private class Impl(
    bucketsDal: BucketsDal,
    searchService: SearchService,
    factory: BucketFactory,
    supervisor: Supervisor[IO],
    running: Ref[IO, Map[BucketId, RunningBucket]]
  ) extends BucketRegistry:

    /**
     * Starts the bucket and replaces the running instance, unless that one has a more recent configuration. The
     * background process of the new instance only starts after the replaced one has stopped, so that a bucket never
     * syncs twice at the same time.
     */
    private[BucketRegistry] def install(stored: StoredBucket): IO[Unit] =
      val bucketId = BucketId(stored.config.id)
      for
        started              <- Deferred[IO, Unit]
        (bucket, background) <- factory(stored.config)
        fiber                <- supervisor.supervise(
                                  started.get >> background.handleErrorWith(e =>
                                    IO(logger.error(s"Background process of bucket '$bucketId' failed", e))
                                  )
                                )
        replaced             <- running.modify { buckets =>
                                  buckets.get(bucketId) match
                                    case Some(current) if current.stored.updatedAt.isAfter(stored.updatedAt) => (buckets, None)
                                    case current                                                             =>
                                      (buckets.updated(bucketId, RunningBucket(stored, bucket, fiber)), Some(current))
                                }
        _                    <- replaced match
                                  case None           => fiber.cancel
                                  case Some(previous) => previous.traverse_(_.fiber.cancel) >> started.complete(()).void
      yield ()

    private def uninstall(bucketId: BucketId): IO[Unit] =
      running.modify(buckets => (buckets - bucketId, buckets.get(bucketId))).flatMap(_.traverse_(_.fiber.cancel))

    /** Brings the running instance in line with the configuration in the database. */
    private def reload(bucketId: BucketId): IO[Unit] =
      bucketsDal.getById(bucketId).flatMap {
        case Some(stored) => install(stored)
        case None         => uninstall(bucketId)
      }

    /**
     * Note: the overlap check against the other buckets is not atomic with the insert or update. Two admins saving
     * buckets with overlapping paths at exactly the same time could both pass it. This is an accepted edge case.
     */
    private def validate(config: ResourceBucketConfig): EitherT[IO, BucketError, ResourceBucketConfig] =
      EitherT(
        bucketsDal.getAll().flatMap { all =>
          val others = all.map(_.config).filterNot(_.id == config.id)
          BucketValidation.validate(withAbsolutePaths(config), others)
        }
      ).leftMap(BucketError.Invalid(_))

    private def checkUpdateAllowed(existing: ResourceBucketConfig, updated: ResourceBucketConfig): Either[BucketError, Unit] =
      (existing, updated) match
        case (e: LocalDirectoryConfig, u: LocalDirectoryConfig) =>
          Either.cond(
            e.hashingAlgorithm == u.hashingAlgorithm,
            (),
            BucketError.Invalid("The hashing algorithm of an existing bucket cannot be changed")
          )

    override def get(bucketId: BucketId): IO[Option[ResourceBucket]] = running.get.map(_.get(bucketId).map(_.bucket))

    override def all: IO[List[ResourceBucket]] = running.get.map(_.values.map(_.bucket).toList.sortBy(_.id))

    override def getConfig(bucketId: BucketId): IO[Option[StoredBucket]] = bucketsDal.getById(bucketId)

    override def allConfigs: IO[List[StoredBucket]] = bucketsDal.getAll()

    override def create(config: ResourceBucketConfig): IO[Either[BucketError, Unit]] =
      val bucketId = BucketId(config.id)
      (for
        validated <- validate(config)
        _         <- EitherT(bucketsDal.insert(validated))
        _         <- EitherT.liftF(reload(bucketId))
        _         <- EitherT.liftF(IO(logger.info(s"Created bucket '$bucketId'")))
      yield ()).value

    override def update(config: ResourceBucketConfig, expectedUpdatedAt: java.time.Instant): IO[Either[BucketError, Unit]] =
      val bucketId = BucketId(config.id)
      (for
        existing  <- EitherT.fromOptionF(bucketsDal.getById(bucketId), BucketError.NotFound(bucketId))
        _         <- EitherT.fromEither[IO](checkUpdateAllowed(existing.config, config))
        validated <- validate(config)
        _         <- EitherT(bucketsDal.update(validated, expectedUpdatedAt))
        _         <- EitherT.liftF(reload(bucketId))
        _         <- EitherT.liftF(IO(logger.info(s"Updated bucket '$bucketId'")))
      yield ()).value

    override def delete(bucketId: BucketId, force: Boolean): IO[Either[BucketError, Unit]] =
      (for
        resourceCount <- EitherT(bucketsDal.delete(bucketId, force))
        _             <- EitherT.liftF(uninstall(bucketId))
        // a sync that was still running may have added resources after the delete
        _             <- EitherT.liftF(bucketsDal.deleteResources(bucketId))
        _             <- EitherT.liftF(searchService.deleteBucket(bucketId))
        _             <- EitherT.liftF(IO(logger.info(s"Deleted bucket '$bucketId' ($resourceCount resources)")))
      yield ()).value
