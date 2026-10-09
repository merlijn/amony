package nl.amony.lib.files

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class GlobPatternsSpec extends AnyWordSpecLike with Matchers {

  private val media = GlobPatterns.unsafeParse(List("**/*.{mp4,mkv,jpg}"))

  "GlobPatterns" should {
    "match files in the root and in sub directories" in {
      media.matches("a.mp4") shouldBe true
      media.matches("x/a.mkv") shouldBe true
      media.matches("x/y/a.jpg") shouldBe true
      media.matches("a.txt") shouldBe false
      media.matches("x/a.mp4/b.txt") shouldBe false
    }

    "match case-insensitively" in {
      media.matches("Holiday.MP4") shouldBe true
      GlobPatterns.unsafeParse(List("Movies/**")).matches("movies/a.mp4") shouldBe true
    }

    "anchor patterns at the root and ignore a leading slash" in {
      val anchored = GlobPatterns.unsafeParse(List("/samples/**"))
      anchored.matches("samples/a.mp4") shouldBe true
      anchored.matches("x/samples/a.mp4") shouldBe false
    }

    "match hidden files and directories" in {
      val hidden = GlobPatterns.unsafeParse(List("**/.*"))
      hidden.matches(".DS_Store") shouldBe true
      hidden.matches("x/.hidden") shouldBe true
      hidden.matches("x/visible.mp4") shouldBe false
    }

    "reject invalid and empty patterns" in {
      GlobPatterns.parse(List("**/*.{mp4")) shouldBe a[Left[?, ?]]
      GlobPatterns.parse(List(" ")) shouldBe a[Left[?, ?]]
      GlobPatterns.parse(List("**/*.mp4", "[a-")) shouldBe Left("Invalid pattern: '[a-'")
    }
  }

}
