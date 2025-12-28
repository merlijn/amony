package nl.amony.lib.tapir

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.setCookie

case class AuthCookies(accessToken: CookieValueWithMeta, refreshToken: CookieValueWithMeta, xsrfToken: CookieValueWithMeta)

object AuthCookies:
  val endpointOutput = (setCookie("access_token") and setCookie("refresh_token") and setCookie("XSRF-TOKEN")).mapTo[AuthCookies]
