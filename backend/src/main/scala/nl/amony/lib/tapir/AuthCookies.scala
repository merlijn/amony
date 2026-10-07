package nl.amony.lib.tapir

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.{EndpointIO, setCookie, setCookieOpt}

import nl.amony.modules.auth.api.{authCookieName, providerIdTokenCookieName, refreshTokenCookieName}

case class AuthCookies(
  accessToken: CookieValueWithMeta,
  refreshToken: CookieValueWithMeta,
  xsrfToken: CookieValueWithMeta,
  providerIdToken: Option[CookieValueWithMeta]
)

object AuthCookies:
  val endpointOutput: EndpointIO[AuthCookies] =
    (setCookie(authCookieName) and setCookie(refreshTokenCookieName) and setCookie("XSRF-TOKEN") and setCookieOpt(
      providerIdTokenCookieName
    )).mapTo[AuthCookies]
