package nl.amony.modules.resources

import cats.data.EitherT
import cats.effect.std.{Mutex, Supervisor}
import cats.effect.{Fiber, IO, Ref, Resource}
import cats.implicits.*
import scribe.Logging

import nl.amony.modules.resources.ResourceConfig.{LocalDirectoryConfig, ResourceBucketConfig}
import nl.amony.modules.resources.api.{BucketId, ResourceBucket}
import nl.amony.modules.resources.dal.{BucketsDal, ResourceDatabase}
import nl.amony.modules.search.api.SearchService

enum BucketError:
  case NotFound(bucketId: BucketId)
  case AlreadyExists(bucketId: BucketId)
  case Invalid(message: String)
  case NotEmpty(bucketId: BucketId, resourceCount: Long)

/** Creates a bucket from its configuration, together with its background (sync) process. */
type BucketFactory = ResourceBucketConfig => IO[(ResourceBucket, IO[Unit])]

trait BucketRegistry:
  def get(bucketId: BucketId): IO[Option[ResourceBucket]]
  def all: IO[List[ResourceBucket]]
  def getConfig(bucketId: BucketId): IO[Option[ResourceBucketConfig]]
  def allConfigs: IO[List[ResourceBucketConfig]]
  def create(config: ResourceBucketConfig): IO[Either[BucketError, Unit]]
  def update(config: ResourceBucketConfig): IO[Either[BucketError, Unit]]

  /** Deletes a bucket from the database and search index. Media files are never deleted. */
  def delete(bucketId: BucketId, force: Boolean): IO[Either[BucketError, Unit]]

object BucketRegistry extends Logging:

  private case class RunningBucket(config: ResourceBucketConfig, bucket: ResourceBucket, fiber: Fiber[IO, Throwable, Unit])

  /**
   * Loads the buckets from the database and starts them. When no buckets exist yet, the default bucket is inserted
   * first.
   */
  def resource(
    defaultBucket: ResourceBucketConfig,
    bucketsDal: BucketsDal,
    resourceDb: ResourceDatabase,
    searchService: SearchService,
    factory: BucketFactory
  ): Resource[IO, BucketRegistry] =
    for
      supervisor <- Supervisor[IO]
      registry   <- Resource.eval {
                      for
                        inserted <- bucketsDal.insertIfNoneExist(defaultBucket)
                        _        <- IO.whenA(inserted)(IO(logger.info(s"No buckets found, inserted default bucket '${defaultBucket.id}'")))
                        mutex    <- Mutex[IO]
                        state    <- Ref.of[IO, Map[BucketId, RunningBucket]](Map.empty)
                        registry  = Impl(bucketsDal, resourceDb, searchService, factory, supervisor, state, mutex)
                        configs  <- bucketsDal.getAll()
                        _        <- configs.traverse_(registry.startBucket)
                      yield registry
                    }
    yield registry

  private class Impl(
    bucketsDal: BucketsDal,
    resourceDb: ResourceDatabase,
    searchService: SearchService,
    factory: BucketFactory,
    supervisor: Supervisor[IO],
    state: Ref[IO, Map[BucketId, RunningBucket]],
    mutex: Mutex[IO]
  ) extends BucketRegistry:

    private[BucketRegistry] def startBucket(config: ResourceBucketConfig): IO[Unit] =
      for
        (bucket, background) <- factory(config)
        fiber                <- supervisor.supervise(
                                  background.handleErrorWith(e => IO(logger.error(s"Background process of bucket '${config.id}' failed", e)))
                                )
        _                    <- state.update(_.updated(bucket.id, RunningBucket(config, bucket, fiber)))
      yield ()

    private def requireRunning(bucketId: BucketId): EitherT[IO, BucketError, RunningBucket] =
      EitherT.fromOptionF(state.get.map(_.get(bucketId)), BucketError.NotFound(bucketId))

    private def otherConfigs(bucketId: BucketId): IO[List[ResourceBucketConfig]] =
      state.get.map(_.values.map(_.config).filterNot(_.id == bucketId).toList)

    private def validate(config: ResourceBucketConfig): EitherT[IO, BucketError, ResourceBucketConfig] =
      EitherT(otherConfigs(BucketId(config.id)).flatMap(others => BucketValidation.validate(config, others))).leftMap(BucketError.Invalid(_))

    private def checkUpdateAllowed(existing: ResourceBucketConfig, updated: ResourceBucketConfig): Either[BucketError, Unit] =
      (existing, updated) match
        case (e: LocalDirectoryConfig, u: LocalDirectoryConfig) =>
          Either.cond(
            e.hashingAlgorithm == u.hashingAlgorithm,
            (),
            BucketError.Invalid("The hashing algorithm of an existing bucket cannot be changed")
          )

    override def get(bucketId: BucketId): IO[Option[ResourceBucket]] = state.get.map(_.get(bucketId).map(_.bucket))

    override def all: IO[List[ResourceBucket]] = state.get.map(_.values.map(_.bucket).toList.sortBy(_.id))

    override def getConfig(bucketId: BucketId): IO[Option[ResourceBucketConfig]] = state.get.map(_.get(bucketId).map(_.config))

    override def allConfigs: IO[List[ResourceBucketConfig]] = state.get.map(_.values.map(_.config).toList.sortBy(_.id))

    override def create(config: ResourceBucketConfig): IO[Either[BucketError, Unit]] =
      val bucketId = BucketId(config.id)
      mutex.lock.surround {
        (for
          exists    <- EitherT.liftF(state.get.map(_.contains(bucketId)))
          _         <- EitherT.cond[IO](!exists, (), BucketError.AlreadyExists(bucketId))
          validated <- validate(config)
          inserted  <- EitherT.liftF(bucketsDal.insert(validated))
          _         <- EitherT.cond[IO](inserted, (), BucketError.AlreadyExists(bucketId))
          _         <- EitherT.liftF(startBucket(validated))
          _         <- EitherT.liftF(IO(logger.info(s"Created bucket '$bucketId'")))
        yield ()).value
      }

    override def update(config: ResourceBucketConfig): IO[Either[BucketError, Unit]] =
      val bucketId = BucketId(config.id)
      mutex.lock.surround {
        (for
          existing  <- requireRunning(bucketId)
          _         <- EitherT.fromEither[IO](checkUpdateAllowed(existing.config, config))
          validated <- validate(config)
          updated   <- EitherT.liftF(bucketsDal.update(validated))
          _         <- EitherT.cond[IO](updated, (), BucketError.NotFound(bucketId))
          _         <- EitherT.liftF(existing.fiber.cancel)
          _         <- EitherT.liftF(startBucket(validated))
          _         <- EitherT.liftF(IO(logger.info(s"Updated bucket '$bucketId'")))
        yield ()).value
      }

    override def delete(bucketId: BucketId, force: Boolean): IO[Either[BucketError, Unit]] =
      mutex.lock.surround {
        (for
          existing      <- requireRunning(bucketId)
          resourceCount <- EitherT.liftF(resourceDb.bucketSize(bucketId))
          _             <- EitherT.cond[IO](force || resourceCount == 0, (), BucketError.NotEmpty(bucketId, resourceCount))
          _             <- EitherT.liftF(existing.fiber.cancel)
          _             <- EitherT.liftF(bucketsDal.deleteWithResources(bucketId))
          _             <- EitherT.liftF(state.update(_ - bucketId))
          _             <- EitherT.liftF(searchService.deleteBucket(bucketId))
          _             <- EitherT.liftF(IO(logger.info(s"Deleted bucket '$bucketId' ($resourceCount resources)")))
        yield ()).value
      }
