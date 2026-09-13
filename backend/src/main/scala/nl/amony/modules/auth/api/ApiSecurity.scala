package nl.amony.modules.auth.api

import java.time.{Duration, Instant}
import java.util.UUID

import scribe.Logging
import sttp.model.Method
import sttp.model.headers.CookieValueWithMeta

import nl.amony.lib.tapir.AuthCookies
import nl.amony.modules.auth.*
import nl.amony.modules.auth.api.{Authentication, JwtDecoder}

class ApiSecurity(authConfig: AuthConfig) extends Logging:

  private val decoder: JwtDecoder  = authConfig.decoder
  private val xsrfProtectedMethods = Set(Method.POST, Method.PUT, Method.PATCH, Method.DELETE)

  private val adminToken = AuthToken(
    userId = UserId("admin"),
    roles  = Set(Role.Admin)
  )

  private def requireXsrfProtection(securityInput: SecurityInput): Either[SecurityError, Unit] =
    for
      xsrfToken   <- securityInput.xsrfCookie.toRight(SecurityError.Unauthorized)
      xXsrfHeader <- securityInput.xXsrfHeader.toRight(SecurityError.Unauthorized)
      _           <- if xsrfToken == xXsrfHeader then Right(()) else Left(SecurityError.Unauthorized)
    yield ()

  def requireSession(securityInput: SecurityInput): Either[SecurityError, AuthToken] = {
    def validateInput =
      for
        accessToken <- securityInput.accessToken.toRight(SecurityError.Unauthorized)
        decoded     <- decoder.decode(accessToken).left.map(_ => SecurityError.Unauthorized)
        _           <-
          if xsrfProtectedMethods.contains(securityInput.method) then requireXsrfProtection(securityInput)
          else Right(())
      yield AuthToken(decoded.userId, decoded.roles)

    if authConfig.enabled then validateInput else Right(adminToken)
  }

  def publicEndpoint(securityInput: SecurityInput): Either[SecurityError, AuthToken] =
    requireSession(securityInput).orElse(Right(AuthToken.anonymous))

  def requireRole(requiredRole: Role)(securityInput: SecurityInput): Either[SecurityError, AuthToken] =
    requireSession(securityInput).flatMap(token =>
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

    val xsrfCookie = CookieValueWithMeta.unsafeApply(
      value    = UUID.randomUUID().toString,
      path     = Some("/"),
      httpOnly = false,
      secure   = authConfig.secureCookies
    )

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
