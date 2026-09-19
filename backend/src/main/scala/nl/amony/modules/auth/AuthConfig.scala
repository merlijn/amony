package nl.amony.modules.auth

import java.security.KeyFactory
import java.security.spec.{ECParameterSpec, ECPoint, ECPrivateKeySpec, ECPublicKeySpec}
import scala.concurrent.duration.*
import scala.language.adhocExtensions
import scala.util.Try

import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}
import pureconfig.*
import pureconfig.error.CannotConvert
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader
import sttp.model.Uri

import nl.amony.modules.auth.api.{AuthToken, JwtDecoder, Permission, Role}

given ConfigReader[Uri] = ConfigReader.fromString[Uri](str => Uri.parse(str).left.map(err => CannotConvert(str, "Uri", err)))

case class RoleAccessConfig(
  hiddenTags: Set[String],
  hiddenBuckets: Set[String],
  permissions: Set[Permission]
) derives ConfigReader

case class IdentityProvider(
  name: String,
  clientId: String,
  clientSecret: String,
  authorizeUrl: Uri,
  tokenUrl: Uri,
  userInfoUrl: Uri,
  scopes: List[String]       = List("openid", "profile", "email"),
  defaultRoles: Set[Role]    = Set.empty,
  // When true the provider is not listed by /api/auth/identity-providers. Used for internal/admin-only providers.
  adminOnly: Option[Boolean] = None
) derives ConfigReader

case class AuthConfig(
  enabled: Boolean,
  requireLogin: Boolean,
  jwt: JwtConfig,
  secureCookies: Boolean,
  oauthStateExpiration: FiniteDuration = 300.seconds,
  identityProviders: List[IdentityProvider],
  accessControl: Map[String, RoleAccessConfig]
) derives ConfigReader {

  val random = new java.security.SecureRandom

  val anonymousAccess: RoleAccessConfig     = accessControl(Role.Anonymous)
  val authenticatedAccess: RoleAccessConfig = accessControl(Role.Authenticated)
  val adminAccess: RoleAccessConfig         = RoleAccessConfig(
    hiddenTags    = Set.empty,
    hiddenBuckets = Set.empty,
    permissions   = Permission.values.toSet
  )

  def access(authToken: AuthToken): RoleAccessConfig =
    if authToken.roles.contains(Role.Anonymous) then anonymousAccess
    else if authToken.roles.contains(Role.Admin) then adminAccess
    else authToken.roles.flatMap(accessControl.get).foldLeft(authenticatedAccess)(merge)

  private def merge(acc: RoleAccessConfig, cfg: RoleAccessConfig): RoleAccessConfig =
    RoleAccessConfig(
      hiddenTags    = acc.hiddenTags intersect cfg.hiddenTags,
      hiddenBuckets = acc.hiddenBuckets intersect cfg.hiddenBuckets,
      permissions   = acc.permissions ++ cfg.permissions
    )

  def decoder = JwtDecoder(jwt.algorithm)
}

case class JwtConfig(accessTokenExpiration: FiniteDuration, refreshTokenExpiration: FiniteDuration, algorithm: JwtAlgorithmConfig)

sealed trait JwtAlgorithmConfig {

  def encode(claim: JwtClaim): String
  def decode(token: String): Try[JwtClaim]
}

object JwtAlgorithmConfig {

  given FieldCoproductHint[JwtAlgorithmConfig] =
    new FieldCoproductHint[JwtAlgorithmConfig]("type"):
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
