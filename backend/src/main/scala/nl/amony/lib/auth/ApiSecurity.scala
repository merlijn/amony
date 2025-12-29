package nl.amony.lib.auth

import cats.effect.IO

import java.time.{Duration, Instant}
import java.util.UUID
import sttp.model.headers.CookieValueWithMeta
import nl.amony.lib.tapir.AuthCookies
import nl.amony.modules.auth.*
import org.http4s.Request
import org.typelevel.ci.CIStringSyntax

enum SecurityError:
  case Unauthorized
  case Forbidden

class ApiSecurity(authConfig: AuthConfig):

  private val decoder: JwtDecoder = authConfig.decoder

  def requireSession(req: Request[IO]): Either[SecurityError, AuthToken] = {
    val securityInput = SecurityInput(
      accessToken = req.cookies.find(_.name == "access_token").map(_.content),
      xsrfCookie  = req.cookies.find(_.name == "XSRF-TOKEN").map(_.content),
      xXsrfHeader = req.headers.get(ci"X-XSRF-TOKEN").map(_.head.value)
    )
    requireSession(securityInput)
  }

  def requireSession(securityInput: SecurityInput): Either[SecurityError, AuthToken] =
    for {
      accessToken <- securityInput.accessToken.toRight(SecurityError.Unauthorized)
      decoded     <- decoder.decode(accessToken).toEither.left.map(_ => SecurityError.Unauthorized)
      xsrfToken   <- securityInput.xsrfCookie.toRight(SecurityError.Unauthorized)
      xXsrfHeader <- securityInput.xXsrfHeader.toRight(SecurityError.Unauthorized)
      _           <- if xsrfToken == xXsrfHeader then Right(()) else Left(SecurityError.Unauthorized)
    } yield AuthToken(decoded.userId, decoded.roles)

  def publicEndpoint(securityInput: SecurityInput): Either[SecurityError, AuthToken] = Right(AuthToken.anonymous)

  def requireRole(requiredRole: Role)(securityInput: SecurityInput): Either[SecurityError, AuthToken] =
    requireSession(securityInput).flatMap(token => if token.roles.contains(requiredRole) then Right(token) else Left(SecurityError.Forbidden))

  def createCookies(apiAuthentication: Authentication): AuthCookies = {
    val accessTokenCookie = CookieValueWithMeta.unsafeApply(
      value    = apiAuthentication.accessToken,
      path     = Some("/"),
      httpOnly = true,
      secure   = authConfig.secureCookies,
      expires  = Some(Instant.now().plus(Duration.ofSeconds(authConfig.jwt.accessTokenExpiration.toSeconds)))
    )

    val refreshCookie = CookieValueWithMeta.unsafeApply(
      value    = apiAuthentication.refreshToken,
      path     = Some("/"),
      httpOnly = true,
      secure   = authConfig.secureCookies,
      expires  = Some(Instant.now().plus(Duration.ofSeconds(authConfig.jwt.refreshTokenExpiration.toSeconds)))
    )

    val xsrfCookie = CookieValueWithMeta
      .unsafeApply(value = UUID.randomUUID().toString, path = Some("/"), httpOnly = false, secure = authConfig.secureCookies)

    AuthCookies(accessTokenCookie, refreshCookie, xsrfCookie)
  }
