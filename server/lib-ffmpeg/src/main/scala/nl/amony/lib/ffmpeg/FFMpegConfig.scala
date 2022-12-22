package nl.amony.lib.ffmpeg

import scala.concurrent.duration.FiniteDuration

case class FFMPegConfig(
   scanParallelFactor: Int,
   ffprobeTimeout: FiniteDuration
)
