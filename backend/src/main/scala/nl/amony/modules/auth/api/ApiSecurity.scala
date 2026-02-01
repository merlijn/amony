package nl.amony.modules.auth.api

import java.time.{Duration, Instant}
import java.util.UUID

import cats.effect.IO
import org.http4s.Request
import org.typelevel.ci.CIStringSyntax
import scribe.Logging
import sttp.model.headers.CookieValueWithMeta

import nl.amony.lib.tapir.AuthCookies
import nl.amony.modules.auth.*
import nl.amony.modules.auth.api.{Authentication, JwtDecoder}

class ApiSecurity(authConfig: AuthConfig) extends Logging:

  private val decoder: JwtDecoder = authConfig.decoder

  private val adminToken = AuthToken(
    userId = UserId("admin"),
    roles  = Set(Role.Admin)
  )

  def requireSession(req: Request[IO]): Either[SecurityError, AuthToken] = {
    val securityInput = SecurityInput(
      accessToken = req.cookies.find(_.name == "access_token").map(_.content),
      xsrfCookie  = req.cookies.find(_.name == "XSRF-TOKEN").map(_.content),
      xXsrfHeader = req.headers.get(ci"X-XSRF-TOKEN").map(_.head.value)
    )
    requireSession(securityInput)
  }

  private def requireXsrfProtection(securityInput: SecurityInput): Either[SecurityError, Unit] =
    for
      xsrfToken   <- securityInput.xsrfCookie.toRight(SecurityError.Unauthorized)
      xXsrfHeader <- securityInput.xXsrfHeader.toRight(SecurityError.Unauthorized)
      _           <- if xsrfToken == xXsrfHeader then Right(()) else Left(SecurityError.Unauthorized)
    yield ()

  def requireSession(securityInput: SecurityInput, xsrfProtection: Boolean = true): Either[SecurityError, AuthToken] = {
    def validateInput =
      for
        accessToken <- securityInput.accessToken.toRight(SecurityError.Unauthorized)
        decoded     <- decoder.decode(accessToken).left.map(_ => SecurityError.Unauthorized)
        _           <- if xsrfProtection then requireXsrfProtection(securityInput) else Right(())
      yield AuthToken(decoded.userId, decoded.roles)

    if authConfig.enabled then validateInput else Right(adminToken)
  }

  def publicEndpoint(securityInput: SecurityInput): Either[SecurityError, AuthToken] =
    requireSession(securityInput).orElse(Right(AuthToken.anonymous))

  def requireRole(requiredRole: Role, xsrfProtection: Boolean = true)(securityInput: SecurityInput): Either[SecurityError, AuthToken] =
    requireSession(securityInput, xsrfProtection).flatMap(token =>
      if token.roles.contains(requiredRole) then Right(token) else Left(SecurityError.Forbidden)
    )

  def userAccess(authToken: AuthToken): UserAccessConfig = authConfig.access(authToken.roles)

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

  def createLogoutCookes = {
    val expiredEmptyCookie =
      CookieValueWithMeta.unsafeApply(
        value    = "",
        path     = Some("/"),
        httpOnly = true,
        secure   = authConfig.secureCookies,
        expires  = Some(Instant.ofEpochSecond(0L))
      )

    AuthCookies(expiredEmptyCookie, expiredEmptyCookie, expiredEmptyCookie)
  }
