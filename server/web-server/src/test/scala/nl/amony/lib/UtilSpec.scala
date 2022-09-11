package nl.amony.lib

import nl.amony.service.fragments.FragmentProtocol.ListOps
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers._
import scribe.Logging

class UtilSpec extends AnyFlatSpecLike with Logging {

  it should ("insert in to list") in {

    List(0, 1, 2).replaceAtPos(0, 42) shouldBe List(42, 1, 2)
    List(0, 1, 2).replaceAtPos(1, 42) shouldBe List(0, 42, 2)
    List(0, 1, 2).replaceAtPos(2, 42) shouldBe List(0, 1, 42)

    // index greater or equal to with results in append
    List(0, 1, 2).replaceAtPos(3, 42) shouldBe List(0, 1, 2, 42)
    List(0, 1, 2).replaceAtPos(4, 42) shouldBe List(0, 1, 2, 42)

    List(0).replaceAtPos(0, 42) shouldBe List(42)
    List.empty.replaceAtPos(0, 42) shouldBe List(42)

    List(0, 1, 2).deleteAtPos(0) shouldBe List(1, 2)
    List(0, 1, 2).deleteAtPos(1) shouldBe List(0, 2)
    List(0, 1, 2).deleteAtPos(2) shouldBe List(0, 1)
    List(0, 1, 2).deleteAtPos(3) shouldBe List(0, 1, 2)
  }
}
