package nl.amony.service.auth

import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}
import pureconfig.*
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader

import java.security.KeyFactory
import java.security.spec.{ECParameterSpec, ECPoint, ECPrivateKeySpec, ECPublicKeySpec}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

case class AuthConfig(
  jwt: JwtConfig,
  adminUsername: String,
  adminPassword: String
) derives ConfigReader


case class JwtConfig(
  accessTokenExpiration: FiniteDuration,
  algorithm: JwtAlgorithmConfig
)

sealed trait JwtAlgorithmConfig {
  
  def encode(claim: JwtClaim): String
  def decode(token: String): Try[JwtClaim]
}

object JwtAlgorithmConfig {

  given FieldCoproductHint[JwtAlgorithmConfig] = new FieldCoproductHint[JwtAlgorithmConfig]("type"):
    override def fieldValue(name: String) = name.dropRight("Config".length)

  given ConfigReader[JwtAlgorithmConfig] = deriveReader
}

case class ES512Config(privateKeyScalar: String,
                       publicKeyX: String,
                       publicKeyY: String,
                       curveName: String = "secp256r1") extends JwtAlgorithmConfig {
  private val algo = JwtAlgorithm.ES512

  import org.bouncycastle.jce.ECNamedCurveTable
  import org.bouncycastle.jce.spec.ECNamedCurveSpec

  private val S = BigInt(privateKeyScalar, 16)
  private val X = BigInt(publicKeyX, 16)
  private val Y = BigInt(publicKeyY, 16)

  private val curveParams = ECNamedCurveTable.getParameterSpec(curveName)
  private val curveSpec: ECParameterSpec = new ECNamedCurveSpec(curveName, curveParams.getCurve(), curveParams.getG(), curveParams.getN(), curveParams.getH())

  private val privateSpec = new ECPrivateKeySpec(S.underlying(), curveSpec)
  private val publicSpec = new ECPublicKeySpec(new ECPoint(X.underlying(), Y.underlying()), curveSpec)

  private val privateKeyEC = KeyFactory.getInstance("ECDSA", "BC").generatePrivate(privateSpec)
  private val publicKeyEC = KeyFactory.getInstance("ECDSA", "BC").generatePublic(publicSpec)
  
  override def encode(claim: JwtClaim) = JwtCirce.encode(claim, privateKeyEC, algo)
  override def decode(token: String)   = JwtCirce.decode(token, publicKeyEC, List(algo))
}

case class HS256Config(secretKey: String) extends JwtAlgorithmConfig {
  private val algo = JwtAlgorithm.HS256
  
  override def encode(claim: JwtClaim) = JwtCirce.encode(claim, secretKey, algo)
  override def decode(token: String)   = JwtCirce.decode(token, secretKey, List(algo))
}
