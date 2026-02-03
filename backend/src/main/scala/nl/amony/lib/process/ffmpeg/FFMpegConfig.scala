package nl.amony.lib.process.ffmpeg

import scala.concurrent.duration.FiniteDuration

case class FFMPegConfig(scanParallelFactor: Int, ffprobeTimeout: FiniteDuration)
