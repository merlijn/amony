package nl.amony.modules.auth

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import nl.amony.lib.auth.*
import nl.amony.lib.tapir.*

case class LoginCredentials(username: String, password: String) derives Schema

object AuthEndpointDefs {

  val errorOutput = {
    val unauthorizedOutput = oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(SecurityError.Unauthorized)
    val forbiddenOutput    = oneOfVariantSingletonMatcher(statusCode(StatusCode.Forbidden))(SecurityError.Forbidden)

    oneOf[SecurityError](unauthorizedOutput, forbiddenOutput)
  }

  val sessionEndpoint: Endpoint[SecurityInput, Unit, SecurityError, AuthToken, Any] =
    endpoint
      .tag("auth").name("getSession").description("Get the current session")
      .get.in("api" / "auth" / "session")
      .securityIn(securityInput)
      .out(jsonBody[AuthToken])
      .errorOut(errorOutput)

  val loginEndpoint =
    endpoint.tag("auth").name("authLogin").description("Login the current user")
      .post.in("api" / "auth" / "login")
      .in(formBody[LoginCredentials]: EndpointIO.Body[String, LoginCredentials])
      .out(RedirectResponse.endpointOutput and AuthCookies.endpointOutput)
      .errorOut(errorOutput)

  val refreshEndpoint =
    endpoint
      .tag("auth").name("authRefreshTokens").description("Refresh the users auth tokens")
      .post.in("api" / "auth" / "refresh")
      .in(cookie[String]("refresh_token"))
      .out(AuthCookies.endpointOutput)
      .errorOut(errorOutput)

  val logoutEndpoint: Endpoint[Unit, Unit, Unit, AuthCookies, Any] =
    endpoint
      .tag("auth").name("authLogout").description("Logout the current user")
      .post.in("api" / "auth" / "logout")
      .out(statusCode(StatusCode.Ok))
      .out(AuthCookies.endpointOutput)

  val oauth2loginEndpoint =
    endpoint.tag("auth").name("authLogin").description("Redirect to OAuth2 provider for login")
      .get.in("api" / "oauth" / "login" / path[String]("provider"))
      .out(RedirectResponse.endpointOutput and setCookie("oauth_login_state"))
      .errorOut(ErrorResponse.endpointOutput)

  val oauth2CallbackEndpoint =
    endpoint.tag("auth").name("authCallback").description("OAuth2 callback endpoint")
      .get.in("api" / "oauth" / "callback" / path[String]("provider"))
      .in(query[String]("code").description("The authorization code returned by the OAuth2 provider"))
      .in(query[String]("state").description("The state parameter returned by the OAuth2 provider"))
      .in(cookie[String]("oauth_login_state").description("The state cookie to prevent CSRF attacks"))
      .out(RedirectResponse.endpointOutput)
      .out(AuthCookies.endpointOutput)
      .errorOut(ErrorResponse.endpointOutput)

  val endpoints = List(loginEndpoint, refreshEndpoint, sessionEndpoint, logoutEndpoint, oauth2loginEndpoint, oauth2CallbackEndpoint)
}
