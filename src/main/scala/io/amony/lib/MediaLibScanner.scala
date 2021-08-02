package io.amony.lib

import akka.actor.typed.ActorRef
import better.files.File
import io.amony.actor.MediaLibActor.{Command, Media, Fragment, RemoveMedia, UpsertMedia}
import io.amony.http.JsonCodecs
import io.amony.lib.FFMpeg.Probe
import io.amony.lib.FileUtil.PathOps
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scribe.Logging

import java.nio.file.{Files, Path}

case class MediaLibConfig(
    libraryPath: Path,
    indexPath: Path,
    scanParallelFactor: Int,
    verifyHashes: Boolean,
    max: Option[Int]
)

object MediaLibScanner extends Logging with JsonCodecs {

  def scan(config: MediaLibConfig, last: List[Media], actorRef: ActorRef[Command]): Unit = {

    implicit val s = Scheduler.global
    val libraryDir = File(config.libraryPath)

    // create the index directory if it does not exist
    val indexDir = File(config.indexPath)
    if (!indexDir.exists)
      indexDir.createDirectory()

    val obs = scanVideosInPath(
      actorRef,
      libraryDir.path,
      config.indexPath,
      config.verifyHashes,
      config.scanParallelFactor,
      config.max,
      last
    )

    val c = Consumer.foreachTask[Media](m =>
      Task {
        actorRef.tell(UpsertMedia(m))
      }
    )

    obs.consumeWith(c).runSyncUnsafe()
  }

  def scanVideo(hash: String, baseDir: Path, videoPath: Path, indexDir: Path): Media = {

    val info      = FFMpeg.ffprobe(videoPath)
    val timeStamp = info.duration / 3
    generateVideoFragment(videoPath, indexDir, hash, timeStamp, timeStamp + 3000)
    val video = asVideo(baseDir, videoPath, hash, info, timeStamp)
    video
  }

  def scanVideosInPath(
      actorRef: ActorRef[Command],
      scanPath: Path,
      indexPath: Path,
      verifyHashes: Boolean,
      parallelFactor: Int,
      max: Option[Int],
      persistedMedia: List[Media],
      extensions: List[String] = List("mp4", "webm")
  )(implicit s: Scheduler): Observable[Media] = {

    val files = FileUtil.walkDir(scanPath)

    val filesTruncated = max match {
      case None    => files
      case Some(n) => files.take(n)
    }

    // first calculate the hashes
    logger.info("Scanning directory for files & calculating hashes...")

    val filesWithHashes: List[(Path, String)] = Observable
      .from(filesTruncated)
      .filter { vid =>
        // filter for extension
        val fileName = vid.getFileName.toString
        extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
      }
      .mapParallelUnordered(parallelFactor) { path =>
        Task {
          val hash = if (verifyHashes) {
            FileUtil.fakeHash(path)
          } else {
            persistedMedia.find(_.uri == scanPath.relativize(path).toString) match {
              case None    => FileUtil.fakeHash(path)
              case Some(m) => m.hash
            }
          }

          (path, hash)
        }
      }
      .consumeWith(Consumer.toList)
      .runSyncUnsafe()

    val (remaining, removed) =
      persistedMedia.partition(m => filesWithHashes.exists { case (_, hash) => hash == m.hash })

    logger.info(s"Scanning done, found ${filesWithHashes.size} files")

    // removed
    removed.foreach { m =>
      logger.info(s"Detected deleted file: ${m.uri}")
      actorRef.tell(RemoveMedia(m.id))
    }

    // moved and new
    Observable
      .from(filesWithHashes)
      .filterNot { case (path, hash) =>
        remaining.exists(m => m.hash == hash && m.uri == scanPath.relativize(path).toString)
      }
      .mapParallelUnordered[Media](parallelFactor) { case (path, hash) =>
        Task {
          val relativePath = scanPath.relativize(path).toString

          remaining.find(_.hash == hash) match {
            case Some(old) =>
              logger.info(s"Detected renamed file: '${old.uri}' -> '${relativePath}'")
              old.copy(uri = relativePath)

            case None =>
              logger.info(s"Scanning new file: '${relativePath}'")
              scanVideo(hash, scanPath, path, indexPath)
          }
        }
      }
  }

  def deleteVideoFragment(indexPath: Path, id: String, from: Long, to: Long): Unit = {

    (indexPath / "thumbnails" / s"${id}-$from.webp").deleteIfExists()
    (indexPath / "thumbnails" / s"${id}-$from-$to.mp4").deleteIfExists()
  }

  def generateVideoFragment(videoPath: Path, indexPath: Path, id: String, from: Long, to: Long): Unit = {

    val thumbnailPath = s"${indexPath}/thumbnails"

    Files.createDirectories(indexPath.resolve("thumbnails"))

    FFMpeg.writeThumbnail(
      inputFile  = videoPath.absoluteFileName(),
      timestamp  = from,
      outputFile = Some(s"${thumbnailPath}/${id}-$from.webp")
    )

    FFMpeg.createMp4(
      inputFile  = videoPath.absoluteFileName(),
      from       = from,
      to         = to,
      outputFile = Some(s"${thumbnailPath}/${id}-$from-$to.mp4")
    )
  }

  protected def asVideo(baseDir: Path, videoPath: Path, hash: String, info: Probe, thumbnailTimestamp: Long): Media = {

    Media(
      id                 = hash,
      uri                = baseDir.relativize(videoPath).toString,
      hash               = hash,
      title              = None,
      duration           = info.duration,
      fps                = info.fps,
      thumbnailTimestamp = thumbnailTimestamp,
      fragments          = List(Fragment(thumbnailTimestamp, thumbnailTimestamp + 3000, None, List.empty)),
      tags               = List.empty,
      resolution         = info.resolution
    )
  }
}
