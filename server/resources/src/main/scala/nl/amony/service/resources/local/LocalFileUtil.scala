package nl.amony.service.resources.local

import nl.amony.lib.files.PathOps
import org.http4s.MediaType

object LocalFileUtil {
  def contentTypeFromPath(path: String): Option[String] = {

    val maybeExt = java.nio.file.Path.of(path).fileExtension

    maybeExt.flatMap { ext =>
      MediaType.extensionMap.get(ext).map(m => s"${m.mainType}/${m.subType}")
    }
  }
}
