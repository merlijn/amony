package nl.amony.modules.resources.api

import scala.concurrent.duration.FiniteDuration

/**
 * Selects the sampling interval for timeline preview tiles from a configurable ladder. The chosen
 * step is the smallest one whose interval is at least `duration / targetFrames`, so short videos get
 * fine-grained previews while long videos use at most the largest configured step.
 */
final class ThumbnailTiles(intervalLadder: List[FiniteDuration], targetFrames: Int):

  require(intervalLadder.nonEmpty, "At least one thumbnail tile interval must be configured")
  require(intervalLadder.forall(_.toMillis > 0), "Thumbnail tile intervals must be positive")
  require(targetFrames > 0, s"Thumbnail tile target frame count must be > 0, got $targetFrames")

  /** Configured intervals in milliseconds, smallest to largest. */
  val intervals: List[Long] = intervalLadder.map(_.toMillis).distinct.sorted

  def intervalInMillis(durationInMillis: Long): Long =
    val target = durationInMillis.toDouble / targetFrames
    intervals.find(_ >= target).getOrElse(intervals.last)
