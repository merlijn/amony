package nl.amony.lib.hash

import org.scalatest.flatspec.AnyFlatSpecLike

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class Base32Spec extends AnyFlatSpecLike {

  it should "do something" in {

    val text = "this is some text to create a hash from"

    val msgDigest = MessageDigest.getInstance("SHA-1")

    val hashBytes = msgDigest.digest(text.getBytes(StandardCharsets.UTF_8))

    val a = Base32.encode(hashBytes)

    val hash15 = new Array[Byte](15)
    hashBytes.copyToArray(hash15, 0, 15)

    val b = Base32.encode(hash15)

    println(a)
    println(b)
  }
}
