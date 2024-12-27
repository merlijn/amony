package nl.amony.service.auth.util

import java.security.spec.ECGenParameterSpec
import java.security.{KeyPairGenerator, SecureRandom}

object GenerateECKeyPair extends App {
  // Initialize the key pair generator
  val keyGen = KeyPairGenerator.getInstance("EC")
  val ecSpec = new ECGenParameterSpec("secp256r1")
  keyGen.initialize(ecSpec, new SecureRandom())

  // Generate the key pair
  val keyPair = keyGen.generateKeyPair()
  val privateKey = keyPair.getPrivate.asInstanceOf[java.security.interfaces.ECPrivateKey]
  val publicKey = keyPair.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey]

  // Get the private key S value
  val s = privateKey.getS
  println(s"Private key (S): ${s.toString(16)}")

  // Get the public key X and Y values
  val w = publicKey.getW
  val x = w.getAffineX
  val y = w.getAffineY
  println(s"Public key (X): ${x.toString(16)}")
  println(s"Public key (Y): ${y.toString(16)}")
}
