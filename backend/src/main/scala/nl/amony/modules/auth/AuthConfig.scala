package nl.amony.modules.auth

import java.security.KeyFactory
import java.security.spec.{ECParameterSpec, ECPoint, ECPrivateKeySpec, ECPublicKeySpec}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}
import pureconfig.*
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader
import sttp.model.Uri

given ConfigReader[Uri] = ConfigReader.fromString[Uri](str => Uri.parse(str).left.map(err => CannotConvert(str, "Uri", err)))

case class OauthProvider(
  name: String,
  clientId: String,
  clientSecret: String,
  host: Uri,
  authorizeEndpoint: String = "authorize",
  tokenEndpoint: String     = "token",
  scopes: List[String]      = List("openid", "profile", "email")
) derives ConfigReader {

  def authorizeUri: Uri = host.addPath(authorizeEndpoint)
  def tokenUri: Uri     = host.addPath(tokenEndpoint)
}

case class AuthConfig(
  jwt: JwtConfig,
  publicUri: Uri,
  secureCookies: Boolean,
  oauthProviders: List[OauthProvider],
  adminUsername: String,
  adminPassword: String
) derives ConfigReader {

  def decoder = JwtDecoder(jwt.algorithm)
}

case class JwtConfig(accessTokenExpiration: FiniteDuration, refreshTokenExpiration: FiniteDuration, algorithm: JwtAlgorithmConfig)

sealed trait JwtAlgorithmConfig {

  def encode(claim: JwtClaim): String
  def decode(token: String): Try[JwtClaim]
}

object JwtAlgorithmConfig {

  given FieldCoproductHint[JwtAlgorithmConfig] = new FieldCoproductHint[JwtAlgorithmConfig]("type"):
    override def fieldValue(name: String): String = name.dropRight("Config".length)

  given ConfigReader[JwtAlgorithmConfig] = deriveReader
}

case class ES512Config(privateKeyScalar: String, publicKeyX: String, publicKeyY: String, curveName: String = "secp256r1") extends JwtAlgorithmConfig {
  private val algo = JwtAlgorithm.ES512

  import org.bouncycastle.jce.ECNamedCurveTable
  import org.bouncycastle.jce.spec.ECNamedCurveSpec

  private val S = BigInt(privateKeyScalar, 16)
  private val X = BigInt(publicKeyX, 16)
  private val Y = BigInt(publicKeyY, 16)

  private val curveParams                = ECNamedCurveTable.getParameterSpec(curveName)
  private val curveSpec: ECParameterSpec =
    new ECNamedCurveSpec(curveName, curveParams.getCurve(), curveParams.getG(), curveParams.getN(), curveParams.getH())

  private val privateSpec = new ECPrivateKeySpec(S.underlying(), curveSpec)
  private val publicSpec  = new ECPublicKeySpec(new ECPoint(X.underlying(), Y.underlying()), curveSpec)

  private val privateKeyEC = KeyFactory.getInstance("ECDSA", "BC").generatePrivate(privateSpec)
  private val publicKeyEC  = KeyFactory.getInstance("ECDSA", "BC").generatePublic(publicSpec)

  override def encode(claim: JwtClaim): String      = JwtCirce.encode(claim, privateKeyEC, algo)
  override def decode(token: String): Try[JwtClaim] = JwtCirce.decode(token, publicKeyEC, List(algo))
}

case class HS256Config(secretKey: String) extends JwtAlgorithmConfig {
  private val algo = JwtAlgorithm.HS256

  override def encode(claim: JwtClaim): String      = JwtCirce.encode(claim, secretKey, algo)
  override def decode(token: String): Try[JwtClaim] = JwtCirce.decode(token, secretKey, List(algo))
}
