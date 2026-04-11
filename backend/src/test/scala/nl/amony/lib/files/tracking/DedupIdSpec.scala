package nl.amony.lib.files.tracking

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path, Paths}

class DedupIdSpec extends AnyWordSpec with Matchers {
  
  "DedupId" should {
    "generate hashes" in {

      val directory: Path = Paths.get("/Users/merlijn/Downloads")

      val filesInDirectory = Files.walk(directory).filter(Files.isRegularFile(_)).toArray.map(_.asInstanceOf[Path])

      filesInDirectory.foreach { file =>
        val hashBytes = DedupId.sampledHash(file)
        val encodedBase64 = java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(hashBytes)
        println(s"$file -> $encodedBase64")
      }
    }
  }
}