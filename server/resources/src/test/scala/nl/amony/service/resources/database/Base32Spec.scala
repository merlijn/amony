package nl.amony.service.resources.database

import nl.amony.service.resources.util.Base32
import org.scalatest.wordspec.AnyWordSpecLike

class Base32Spec extends AnyWordSpecLike {
  "Base32" should {
    "encode" in {

      // single byte
      assert(Base32.encode(Array(0x00.toByte)) == "aa") // 00000|000
      assert(Base32.encode(Array(0x01.toByte)) == "ab") // 00000|001
      assert(Base32.encode(Array(0x88.toByte)) == "ra") // 10001|000
      assert(Base32.encode(Array(0xff.toByte)) == "7h") // 11111|111

      // 2 bytes
      assert(Base32.encode(Array(0x00.toByte, 0x00.toByte)) == "aaaa") // 00000|00000|00000|0
      assert(Base32.encode(Array(0x88.toByte, 0x88.toByte)) == "rcea") // 10001|00010|00100|0
      assert(Base32.encode(Array(0xff.toByte, 0xff.toByte)) == "777b") // 11111|11111|11111|1

      // 5 bytes
      assert(Base32.encode(Array(0x00.toByte, 0x00.toByte, 0x00.toByte, 0x00.toByte, 0x00.toByte)) == "aaaaaaaa") // 00000|00000|00000|00000|00000|00000|00000|00000
      assert(Base32.encode(Array(0x88.toByte, 0x88.toByte, 0x88.toByte, 0x88.toByte, 0x88.toByte)) == "rceircei") // 10001|00010|00100|01000|10001|00010|00100|01000
      assert(Base32.encode(Array(0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte)) == "77777777") // 11111|11111|11111|11111|11111|11111|11111|11111
    }
  }
}
