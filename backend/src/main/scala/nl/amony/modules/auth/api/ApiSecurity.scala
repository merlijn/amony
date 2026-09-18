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
    roles  = Set(Role.Admin, Role.Authenticated)
  )

  private def requireXsrfProtection(securityInput: SecurityInput): Either[SecurityError, Unit] =
    for
      xsrfToken   <- securityInput.xsrfCookie.toRight(SecurityError.Unauthorized)
      xXsrfHeader <- securityInput.xXsrfHeader.toRight(SecurityError.Unauthorized)
      _           <- if xsrfToken == xXsrfHeader then Right(()) else Left(SecurityError.Unauthorized)
    yield ()

  /**
   * Resolves an auth token from a raw access token value, without any XSRF checks.
   * Intended for non-Tapir (http4s) routes that need to evaluate access control.
   */
  def decodeAccessToken(accessToken: Option[String]): AuthToken =
    if !authConfig.enabled then adminToken
    else
      accessToken match
        case None              => AuthToken.anonymous
        case Some(accessToken) =>
          decoder.decode(accessToken) match
            case Left(_)        => AuthToken.anonymous
            case Right(decoded) => AuthToken(decoded.userId, decoded.roles + Role.Authenticated)

  private def resolveToken(securityInput: SecurityInput): Either[SecurityError, AuthToken] =
    val token = decodeAccessToken(securityInput.accessToken)
    if authConfig.enabled && !token.roles.contains(Role.Anonymous) && xsrfProtectedMethods.contains(securityInput.method) then
      requireXsrfProtection(securityInput).map(_ => token)
    else Right(token)

  def authorize(requiredPermission: Option[Permission] = None)(securityInput: SecurityInput): Either[SecurityError, AuthToken] =
    resolveToken(securityInput).flatMap { token =>
      requiredPermission match
        case None             => Right(token)
        case Some(permission) =>
          if userAccess(token).permissions.contains(permission) then Right(token)
          else if token.roles.contains(Role.Anonymous) then Left(SecurityError.Unauthorized)
          else Left(SecurityError.Forbidden)
    }

  def userAccess(authToken: AuthToken): RoleAccessConfig = authConfig.access(authToken)

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
