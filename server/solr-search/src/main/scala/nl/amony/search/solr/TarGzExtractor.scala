package nl.amony.search.solr

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

import java.io.{BufferedInputStream, FileOutputStream}
import java.nio.file.{Files, Path, Paths}
import scala.util.Using

object TarGzExtractor {
  def extractResourceTarGz(resourcePath: String, targetDirectory: Path): Unit = {
    // Get the resource as a stream
    val resourceStream = getClass.getResourceAsStream(resourcePath)
    if (resourceStream == null) {
      throw new RuntimeException(s"Resource not found: $resourcePath")
    }

    // Create target directory if it doesn't exist
    Files.createDirectories(targetDirectory)

    Using.resource(new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(resourceStream)))) { tarIn =>
      var entry = tarIn.getNextTarEntry
      while (entry != null) {
        val targetPath = targetDirectory.resolve(entry.getName)

        if (entry.isDirectory) {
          Files.createDirectories(targetPath)
        } else {
          // Create parent directories if they don't exist
          Files.createDirectories(targetPath.getParent)

          // Extract file
          Using(new FileOutputStream(targetPath.toFile)) { output =>
            val buffer = new Array[Byte](1024)
            var len = tarIn.read(buffer)
            while (len != -1) {
              output.write(buffer, 0, len)
              len = tarIn.read(buffer)
            }
          }
        }

        entry = tarIn.getNextTarEntry
      }
    }
  }
}
