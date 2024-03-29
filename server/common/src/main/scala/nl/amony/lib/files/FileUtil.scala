package nl.amony.lib.files

import scribe.Logging

import java.nio.file.Path

object FileUtil extends Logging {

  // strip extension
  def stripExtension(fileName: String): String = {
    val dotIdx = fileName.lastIndexOf('.')
    val last   = if (dotIdx >= 0) dotIdx else fileName.length
    fileName.substring(0, last)
  }
}
