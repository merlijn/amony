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

import nl.amony.modules.auth.api.{AuthToken, JwtDecoder, Permission, Role, RolesFrom}

given ConfigReader[Uri] = ConfigReader.fromString[Uri](str => Uri.parse(str).left.map(err => CannotConvert(str, "Uri", err)))

case class RoleAccessConfig(
  permissions: Set[Permission]
) derives ConfigReader

/**
 * Additional headers sent on the server-side token and userinfo requests (not the browser redirect),
 * given as a comma-separated list of `Name: Value` pairs. Needed when the app reaches the provider
 * under a host that differs from its public domain, e.g. Zitadel's
 * `X-Zitadel-Instance-Host: <public-host>`.
 */
case class ExtraHeaders(values: Map[String, String])

object ExtraHeaders:

  val empty: ExtraHeaders = ExtraHeaders(Map.empty)

  /** Parses a comma-separated `Name: Value` list; a value may itself contain colons. */
  def parse(raw: String): Either[String, ExtraHeaders] =
    raw
      .split(",")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(entry =>
        entry.split(":", 2) match
          case Array(name, value) if name.trim.nonEmpty => Right(name.trim -> value.trim)
          case _                                        => Left(s"'$entry' is not a 'Name: Value' pair")
      )
      .foldLeft[Either[String, Map[String, String]]](Right(Map.empty)):
        case (Right(acc), Right(pair)) => Right(acc + pair)
        case (Left(err), _)            => Left(err)
        case (_, Left(err))            => Left(err)
      .map(ExtraHeaders.apply)

  given ConfigReader[ExtraHeaders] =
    ConfigReader.fromString[ExtraHeaders](raw => parse(raw).left.map(reason => CannotConvert(raw, "ExtraHeaders", reason)))

/**
 * Roles given as a comma-separated list (e.g. `admin,user`), so a provider's default roles can be
 * set from a single environment variable. Empty by default: roles are only granted when the provider
 * actually supplies them.
 */
case class DefaultRoles(values: Set[Role])

object DefaultRoles:

  val empty: DefaultRoles = DefaultRoles(Set.empty)

  given ConfigReader[DefaultRoles] =
    ConfigReader.fromString[DefaultRoles](raw => Right(DefaultRoles(raw.split(",").iterator.map(_.trim).filter(_.nonEmpty).map(Role.apply).toSet)))

case class IdentityProvider(
  name: String,
  clientId: String,
  clientSecret: String,
  authorizeUrl: Uri,
  tokenUrl: Uri,
  userInfoUrl: Uri,
  // Optional OIDC end-session endpoint (RP-Initiated Logout). When set, logout redirects the
  // browser there so the upstream identity provider session is ended as well.
  endSessionUrl: Option[Uri] = None,
  scopes: List[String]       = List("openid", "profile", "email"),
  // Roles granted when the provider supplies none through a roles claim. Empty by default, so a
  // config mistake cannot silently grant a role (e.g. admin) to every user who logs in.
  defaultRoles: DefaultRoles = DefaultRoles.empty,
  // Dot-path to the roles claim in the userinfo response, e.g. "groups" (Dex), "realm_access.roles"
  // (Keycloak) or "urn:zitadel:iam:org:project:roles" (Zitadel). When absent, or missing from the
  // response, `defaultRoles` is used.
  rolesClaim: Option[String] = None,
  // Shape of the roles claim value: an array of role names, or an object whose keys are the roles.
  rolesFrom: RolesFrom       = RolesFrom.Array,
  // Additional headers sent on the server-side token and userinfo requests (not the browser
  // redirect). Needed when the app reaches the provider under a host that differs from its public
  // domain, e.g. Zitadel's `X-Zitadel-Instance-Host: <public-host>`.
  extraHeaders: ExtraHeaders = ExtraHeaders.empty
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
    permissions = Permission.values.toSet
  )

  def access(authToken: AuthToken): RoleAccessConfig =
    if authToken.roles.contains(Role.Anonymous) then anonymousAccess
    else if authToken.roles.contains(Role.Admin) then adminAccess
    else authToken.roles.flatMap(accessControl.get).foldLeft(authenticatedAccess)(merge)

  private def merge(acc: RoleAccessConfig, cfg: RoleAccessConfig): RoleAccessConfig =
    RoleAccessConfig(
      permissions = acc.permissions ++ cfg.permissions
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
