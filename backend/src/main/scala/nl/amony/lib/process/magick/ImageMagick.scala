package nl.amony.lib.process.magick

import nl.amony.lib.process.ProcessRunner
import nl.amony.lib.process.magick.tasks.{CreateThumbnail, GetImageMetaData}

object ImageMagick extends ProcessRunner with GetImageMetaData with CreateThumbnail {}
