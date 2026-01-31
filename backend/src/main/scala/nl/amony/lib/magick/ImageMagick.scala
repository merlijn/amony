package nl.amony.lib.magick

import nl.amony.lib.ffmpeg.tasks.ProcessRunner
import nl.amony.lib.magick.tasks.{CreateThumbnail, GetImageMetaData}

object ImageMagick extends ProcessRunner with GetImageMetaData with CreateThumbnail {}
