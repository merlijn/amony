package nl.amony.service.resources

import org.scalatest.flatspec.AnyFlatSpecLike

class IOResponseSpec extends AnyFlatSpecLike {

  it should "foo" in {

    IOResponse.fromPath("/Users/merlijn/dev/amony/media/nature/plateau.mp4").foreach {
      io => println(io.contentType())
    }
  }
}
