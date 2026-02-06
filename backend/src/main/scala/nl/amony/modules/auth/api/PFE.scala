package nl.amony.modules.auth.api

import org.bouncycastle.crypto.fpe.FPEFF1Engine
import org.bouncycastle.crypto.params.{FPEParameters, KeyParameter}

class FPE(radix: Int, key: Array[Byte], tweak: Array[Byte]):

  private val engine = new FPEFF1Engine()
  engine.init(true, new FPEParameters(new KeyParameter(key), radix, tweak))

  def encrypt(value: Int): String =
    synchronized {
      val input = f"$value%08x".getBytes("UTF-8")
      val output = new Array[Byte](8)
      engine.processBlock(input, 0, 8, output, 0)
      new String(output)
    }
