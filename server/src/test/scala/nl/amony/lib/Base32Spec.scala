package nl.amony.lib

import org.scalacheck.{Gen, Prop}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.Checkers

import java.util
import scala.util.Random

class Base32Spec extends AnyFlatSpecLike with Matchers with Checkers {

  val byteGen = Gen.chooseNum(0, 255).map(_.toByte)
  val byteArrayGen = Gen.listOfN(20, byteGen).map(_.toArray)

//  it should "identity property should hold for decode(encode(mesg))" in {
//
//    val b62 = Base62.createInstance()
//
//    val prop = Prop.forAll(Gen.stringOfN(32, Gen.alphaChar)) { str =>
//      val msg = str.getBytes()
//
//      val encoded = b62.encode(msg)
//      val decoded = b62.decode(encoded)
//
//      val containsOnlyBase62 = encoded.forall(c => Base62.CharacterSets.GMP.contains(c))
//      val identityProp = util.Arrays.equals(decoded, msg)
//
//      containsOnlyBase62 && identityProp
//    }
//
//    check(prop)
//  }
}
