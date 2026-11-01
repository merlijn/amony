package nl.amony.modules.resources.api

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration

import nl.amony.modules.resources.util.Base32

/**
 * Selects the sampling interval for timeline preview tiles from a configurable ladder. The chosen
 * step is the smallest one whose interval is at least `duration / targetFrames`, so short videos get
 * fine-grained previews while long videos use at most the largest configured step.
 */
final class ThumbnailTiles(
  intervalLadder: List[FiniteDuration],
  targetFrames: Int,
  val spritePixels: Int = VideoThumbnailTile.defaultPixels
):

  require(intervalLadder.nonEmpty, "At least one thumbnail tile interval must be configured")
  require(intervalLadder.forall(_.toMillis > 0), "Thumbnail tile intervals must be positive")
  require(targetFrames > 0, s"Thumbnail tile target frame count must be > 0, got $targetFrames")
  require(spritePixels > 0, s"Thumbnail tile sprite pixel count must be > 0, got $spritePixels")

  /** Configured intervals in milliseconds, smallest to largest. */
  val intervals: List[Long] = intervalLadder.map(_.toMillis).distinct.sorted

  def intervalInMillis(durationInMillis: Long): Long =
    val target = durationInMillis.toDouble / targetFrames
    intervals.find(_ >= target).getOrElse(intervals.last)

  private val recipeTokenLength = 8

  /**
   * Stable token for everything that determines the sprite geometry and manifest layout. It is part
   * of the public manifest and sprite URLs, so a configuration change cannot make a cached manifest
   * reference sprites with a different geometry.
   */
  lazy val recipeToken: String = {
    val parts  = List(
      spritePixels.toString,
      VideoThumbnailTile.columnsPerSprite.toString,
      VideoThumbnailTile.framesPerSprite.toString,
      VideoThumbnailTile.spriteQuality.toString,
      intervals.mkString(","),
      targetFrames.toString,
      VideoThumbnailTile.manifestVersion.toString,
      ResourceOperations.previewRecipeVersion.toString
    )
    val digest = MessageDigest.getInstance("SHA-1")
    Base32.encode(digest.digest(parts.mkString("\u0000").getBytes(StandardCharsets.UTF_8))).take(recipeTokenLength)
  }
