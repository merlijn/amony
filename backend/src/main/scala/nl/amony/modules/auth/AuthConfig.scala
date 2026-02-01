package nl.amony.modules.auth

import java.security.KeyFactory
import java.security.spec.{ECParameterSpec, ECPoint, ECPrivateKeySpec, ECPublicKeySpec}
import scala.concurrent.duration.FiniteDuration
import scala.language.adhocExtensions
import scala.util.Try

import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}
import pureconfig.*
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.derivation.EnumConfigReader
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader
import sttp.model.Uri

import nl.amony.modules.auth.api.{JwtDecoder, Role}

given ConfigReader[Uri] = ConfigReader.fromString[Uri](str => Uri.parse(str).left.map(err => CannotConvert(str, "Uri", err)))

enum Action derives EnumConfigReader:
  case Search
  case Preview
  case View
  case Download
  case Upload

case class UserAccessConfig(
  hiddenTags: Set[String],
  hiddenBuckets: Set[String],
  allowedActions: Set[Action]
) derives ConfigReader

case class OauthProvider(
  name: String,
  clientId: String,
  clientSecret: String,
  host: Uri,
  authorizeEndpoint: String = "authorize",
  tokenEndpoint: String     = "token",
  scopes: List[String]      = List("openid", "profile", "email"),
  defaultRoles: Set[Role]   = Set.empty
) derives ConfigReader {

  def authorizeUri: Uri = host.addPath(authorizeEndpoint.split("/").filter(_.nonEmpty))
  def tokenUri: Uri     = host.addPath(tokenEndpoint.split("/").filter(_.nonEmpty))
}

case class AuthConfig(
  enabled: Boolean,
  jwt: JwtConfig,
  publicUri: Uri,
  secureCookies: Boolean,
  oauthProviders: List[OauthProvider],
  accessControl: Map[String, UserAccessConfig]
) derives ConfigReader {

  val anonymousAccess: UserAccessConfig     = accessControl("anonymous")
  val authenticatedAccess: UserAccessConfig = accessControl("authenticated")
  val adminAccess: UserAccessConfig         = UserAccessConfig(
    hiddenTags     = Set.empty,
    hiddenBuckets  = Set.empty,
    allowedActions = Set(Action.Search, Action.Preview, Action.View, Action.Download, Action.Upload)
  )

  def access(roles: Set[Role]): UserAccessConfig = {

    def roleAccess(role: Role): UserAccessConfig =
      role match
        case Role.Admin => adminAccess
        case _          => accessControl.getOrElse(role, authenticatedAccess)

    def merge(configs: Set[UserAccessConfig]): UserAccessConfig =
      configs.foldLeft(anonymousAccess) { (acc, cfg) =>
        UserAccessConfig(
          hiddenTags     = acc.hiddenTags intersect cfg.hiddenTags,
          hiddenBuckets  = acc.hiddenBuckets intersect cfg.hiddenBuckets,
          allowedActions = acc.allowedActions ++ cfg.allowedActions
        )
      }

    merge(roles.map(roleAccess))
  }

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
