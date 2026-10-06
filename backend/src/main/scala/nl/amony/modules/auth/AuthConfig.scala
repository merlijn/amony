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
import pureconfig.generic.derivation.EnumConfigReader
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader
import sttp.model.Uri

import nl.amony.modules.auth.api.{AuthToken, JwtDecoder, Permission, Role, RolesFrom}

given ConfigReader[Uri] = ConfigReader.fromString[Uri](str => Uri.parse(str).left.map(err => CannotConvert(str, "Uri", err)))

case class RoleAccessConfig(
  permissions: Set[Permission]
) derives ConfigReader

/**
 * A named identity provider preset. It only supplies defaults for how the roles claim is read from
 * the userinfo response; `roles-claim` and `roles-from` on the provider override them.
 */
enum ProviderType derives EnumConfigReader:
  case Generic, Dex, Keycloak, Zitadel, Casdoor

object ProviderType:

  def defaultRolesClaim(providerType: ProviderType): Option[String] = providerType match
    case ProviderType.Generic  => None
    case ProviderType.Dex      => Some("groups")
    case ProviderType.Keycloak => Some("realm_access.roles")
    case ProviderType.Zitadel  => Some("urn:zitadel:iam:org:project:roles")
    case ProviderType.Casdoor  => Some("roles")

  def defaultRolesFrom(providerType: ProviderType): RolesFrom = providerType match
    case ProviderType.Zitadel => RolesFrom.ObjectKeys
    case _                    => RolesFrom.Array

case class IdentityProvider(
  name: String,
  clientId: String,
  clientSecret: String,
  authorizeUrl: Uri,
  tokenUrl: Uri,
  userInfoUrl: Uri,
  // Optional OIDC end-session endpoint (RP-Initiated Logout). When set, logout redirects the
  // browser there so the upstream identity provider session is ended as well.
  endSessionUrl: Option[Uri]   = None,
  scopes: List[String]         = List("openid", "profile", "email"),
  // Roles assigned when the provider supplies none through a roles claim. Keep this empty (or a
  // low-privilege role) in production: with a claim configured, absent roles fall back to these.
  defaultRoles: Set[Role]      = Set.empty,
  // When true the provider is not listed by /api/auth/identity-providers. Used for internal/admin-only providers.
  adminOnly: Option[Boolean]   = None,
  // Preset supplying defaults for the two fields below; see ProviderType.
  providerType: ProviderType   = ProviderType.Generic,
  // Dot-path to the roles claim in the userinfo response, e.g. "realm_access.roles" or "groups".
  rolesClaim: Option[String]   = None,
  // How to interpret the roles claim value.
  rolesFrom: Option[RolesFrom] = None,
  // Extra headers sent on the server-side token and userinfo requests (not the browser redirect).
  // Needed when the app reaches the provider under a host that differs from its public domain, e.g.
  // Zitadel's `X-Zitadel-Instance-Host: <public-host>`.
  headers: Map[String, String] = Map.empty
) derives ConfigReader:

  def effectiveRolesClaim: Option[String] = rolesClaim.orElse(ProviderType.defaultRolesClaim(providerType))

  def effectiveRolesFrom: RolesFrom = rolesFrom.getOrElse(ProviderType.defaultRolesFrom(providerType))

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
