package nl.amony.modules.auth.crypt

import org.bouncycastle.crypto.fpe.FPEFF1Engine
import org.bouncycastle.crypto.params.{FPEParameters, KeyParameter}

import nl.amony.modules.auth.crypt.FPEId.*

object FPEId:
  private val radix            = 16
  private val versionHexDigits = 2

  // Derive obfuscation byte from encrypted value (e.g., first byte)
  private def obfuscationByte(encryptedHex: String): Byte =
    Integer.parseInt(encryptedHex.take(2), radix).toByte

  def extractVersion(encrypted: String): Byte =
    require(encrypted.length > versionHexDigits, "Encrypted string too short")
    val versionPart       = encrypted.take(versionHexDigits)
    val encryptedValue    = encrypted.drop(versionHexDigits)
    val obfuscatedVersion = Integer.parseInt(versionPart, radix).toByte
    (obfuscatedVersion ^ obfuscationByte(encryptedValue)).toByte

class FPEId(key: Array[Byte], tweak: Array[Byte], bitSize: Int = 32):

  require(bitSize % 4 == 0, "bitSize must be a multiple of 4 for hex encoding")
  require(bitSize > 0 && bitSize <= 64, "bitSize must be between 4 and 64")

  private val valueHexDigits = bitSize / 4
  private val totalHexDigits = versionHexDigits + valueHexDigits
  private val maxValue: Long = if bitSize == 64 then Long.MaxValue else (1L << bitSize) - 1

  private val encryptEngine = new FPEFF1Engine()
  encryptEngine.init(true, new FPEParameters(new KeyParameter(key), radix, tweak))

  private val decryptEngine = new FPEFF1Engine()
  decryptEngine.init(false, new FPEParameters(new KeyParameter(key), radix, tweak))

  private def process(engine: FPEFF1Engine, hex: String): Array[Byte] = {
    val input  = hex.map(c => Character.digit(c, radix).toByte).toArray
    val output = new Array[Byte](valueHexDigits)
    engine.processBlock(input, 0, valueHexDigits, output, 0)
    output
  }

  def encrypt(value: Long, version: Byte = 0): String =
    synchronized {
      require(value >= 0, s"Value must be non-negative, got: $value")
      require(value <= maxValue, s"Value $value exceeds max for $bitSize bits ($maxValue)")

      val valueHex = s"%0${valueHexDigits}x".format(value)
      val output   = process(encryptEngine, valueHex)

      val encryptedValue    = output.map(b => Character.forDigit(b, radix)).mkString
      val obfuscatedVersion = (version ^ obfuscationByte(encryptedValue)).toByte
      f"${obfuscatedVersion & 0xff}%02x" + encryptedValue
    }

  def decrypt(encrypted: String): (version: Byte, value: Long) =
    synchronized {
      require(encrypted.length == totalHexDigits, s"Encrypted string must be $totalHexDigits characters")

      val encryptedValue   = encrypted.drop(versionHexDigits)
      val recoveredVersion = extractVersion(encrypted)
      val output           = process(decryptEngine, encryptedValue)

      val decryptedValue = java.lang.Long.parseUnsignedLong(output.map(b => Character.forDigit(b, radix)).mkString, radix)

      (recoveredVersion, decryptedValue)
    }
