package nl.amony.service.resources

import org.scalatest.flatspec.AnyFlatSpecLike

class ResourceContentSpec extends AnyFlatSpecLike {

  it should "foo" in {

    ResourceContent.fromPath("/Users/merlijn/dev/amony/media/nature/plateau.mp4").foreach {
      io => println(io.contentType())
    }
  }
}
