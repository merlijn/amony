package nl.amony.lib.tapir

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.{EndpointIO, setCookie}

case class AuthCookies(accessToken: CookieValueWithMeta, refreshToken: CookieValueWithMeta, xsrfToken: CookieValueWithMeta)

object AuthCookies:
  val endpointOutput: EndpointIO[AuthCookies] = (setCookie("access_token") and setCookie("refresh_token") and setCookie("XSRF-TOKEN")).mapTo[AuthCookies]
