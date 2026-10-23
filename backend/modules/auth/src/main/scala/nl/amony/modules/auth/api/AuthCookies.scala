package nl.amony.modules.auth.api

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.{EndpointIO, setCookieOpt}

/** The auth cookies a response may set. All are optional: a request that does not change the session sets none. */
case class AuthCookies(
  accessToken: Option[CookieValueWithMeta],
  refreshToken: Option[CookieValueWithMeta],
  xsrfToken: Option[CookieValueWithMeta],
  providerIdToken: Option[CookieValueWithMeta]
)

object AuthCookies:

  val empty: AuthCookies = AuthCookies(None, None, None, None)

  val endpointOutput: EndpointIO[AuthCookies] =
    setCookieOpt(authCookieName)
      .and(setCookieOpt(refreshTokenCookieName))
      .and(setCookieOpt("XSRF-TOKEN"))
      .and(setCookieOpt(providerIdTokenCookieName))
      .mapTo[AuthCookies]
