package nl.amony.modules.resources.impl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.IO
import cats.implicits.*
import scribe.Logging

import nl.amony.modules.resources.api.ResourceOperations
import nl.amony.modules.resources.api.{BucketId, LocalDirectoryConfig, ResourceBucketConfig, ResourceId, ResourceIndex, ResourceInfo}
import nl.amony.modules.resources.dal.{BucketsDal, ResourceDatabase}
import nl.amony.modules.resources.util.PartialHash as PartialHashUtil

/**
 * One-off migration to the current partial hash recipe. It recomputes every resource hash, renames cached derived files
 * to the new content version and reindexes Solr. Safe to run more than once; remove after all buckets have migrated.
 */
object PartialHashMigration extends Logging {

  final private[resources] case class HashUpdate(resourceId: ResourceId, hash: String, oldVersions: Set[String], newVersion: String)

  def run(
    db: ResourceDatabase,
    bucketsDal: BucketsDal,
    index: ResourceIndex,
    parallelFactor: Int
  ): IO[Unit] =
    for
      _       <- IO(logger.info("Running one-off partial hash migration"))
      configs <- bucketsDal.getAll().map(_.map(_.config))
      _       <- configs.traverse_(migrateBucket(db, _, parallelFactor))
      _       <- configs.traverse_(config => index.reindex(BucketId(config.id), db.getStream(BucketId(config.id))))
      _       <- IO(logger.info("Finished one-off partial hash migration"))
    yield ()

  /**
   * Hashes run in parallel and only update the database after the cached files have been renamed. A crash in between
   * is safe: the next run recomputes the same hashes, finds the files already renamed and then writes the database.
   */
  private def migrateBucket(db: ResourceDatabase, config: ResourceBucketConfig, parallelFactor: Int): IO[Unit] =
    config match
      case local: LocalDirectoryConfig =>
        val bucketId = BucketId(local.id)
        db.getStream(bucketId)
          .parEvalMapUnordered(math.max(1, parallelFactor))(migrateResource(local, _))
          .compile
          .toList
          .flatMap { updates =>
            val changed = updates.flatten
            IO(logger.info(s"Recomputing partial hashes for ${changed.size} resources in bucket '${local.id}'")) >>
              renameDerivedFiles(local.cachePath, changed) >>
              changed.parTraverse_(update => db.updatePartialHash(bucketId, update.resourceId, update.hash))
          }

  private def migrateResource(config: LocalDirectoryConfig, info: ResourceInfo): IO[Option[HashUpdate]] =
    if PartialHashUtil.isCurrent(info.partialHash) then IO.pure(None)
    else
      val source = config.resourcePath.resolve(info.path)
      config.hashingAlgorithm.createHash(source).attempt.map {
        case Left(error) =>
          logger.warn(s"Skipping partial hash migration for '${config.id}/${info.path}': could not hash $source", error)
          None
        case Right(hash) =>
          val updated    = info.copy(partialHash = hash)
          val newVersion = ResourceOperations.contentVersion(updated)
          Some(HashUpdate(info.resourceId, hash, oldContentVersions(info), newVersion))
      }

  /**
   * Version-token values that cached derived files may carry for this resource. Hash-less legacy rows were named using
   * an empty hash, which the database migration replaced with the placeholder before this class runs.
   */
  private def oldContentVersions(info: ResourceInfo): Set[String] =
    val direct = ResourceOperations.contentVersion(info)
    if info.partialHash == PartialHashUtil.Placeholder then
      Set(direct, ResourceOperations.contentVersion(info.copy(partialHash = "")))
    else Set(direct)

  private[resources] def renameDerivedFiles(cachePath: Path, updates: List[HashUpdate]): IO[Unit] =
    IO.blocking {
      if updates.nonEmpty && Files.isDirectory(cachePath) then
        val byResource = updates.map(update => update.resourceId.toString -> update).toMap
        Using.resource(Files.list(cachePath)) { stream =>
          stream.iterator().asScala
            .foreach(file => byResource.get(resourceIdOf(file)).foreach(renameDerivedFile(_, file)))
        }
    }

  private def resourceIdOf(file: Path): String =
    val name = file.getFileName.toString
    val end  = name.indexOf('_')
    if end < 0 then name else name.substring(0, end)

  private def renameDerivedFile(update: HashUpdate, file: Path): Unit = {
    val name = file.getFileName.toString

    update.oldVersions
      .filterNot(_ == update.newVersion)
      .find(version => name.startsWith(update.resourceId.toString + s"_${version}_"))
      .foreach { oldVersion =>
        val prefix     = update.resourceId.toString + s"_${oldVersion}_"
        val targetName = update.resourceId.toString + s"_${update.newVersion}_" + name.substring(prefix.length)
        val target     = file.resolveSibling(targetName)

        try
          if targetName.endsWith(".vtt") then
            val content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            Files.writeString(target, content.replace(oldVersion, update.newVersion), StandardCharsets.UTF_8)
            Files.deleteIfExists(file)
          else Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
        catch
          case error: Exception =>
            logger.warn(s"Failed to migrate cached file '$file' to '$target'", error)
      }
  }
}
