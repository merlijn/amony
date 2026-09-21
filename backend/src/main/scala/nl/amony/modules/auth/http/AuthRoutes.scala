package nl.amony.modules.auth.http

import java.time.Instant
import scala.jdk.DurationConverters.*

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import io.circe.Codec
import org.http4s.HttpRoutes
import scribe.Logging
import sttp.model.StatusCode
import sttp.model.headers.Cookie.SameSite
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.http4s.Http4sServerOptions

import nl.amony.lib.tapir.*
import nl.amony.lib.tapir.dsl.{RoutesModule, routes, serverLogic, serverLogicT}
import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.auth.api.*

case class IdentityProviderDto(name: String, loginUrl: String) derives Codec, Schema
case class LogoutResponse(logoutUrl: Option[String]) derives Codec, Schema

object AuthRoutes extends RoutesModule, Logging:

  val errorOutput = {
    val unauthorizedOutput = oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(SecurityError.Unauthorized)
    val forbiddenOutput    = oneOfVariantSingletonMatcher(statusCode(StatusCode.Forbidden))(SecurityError.Forbidden)

    oneOf[SecurityError](unauthorizedOutput, forbiddenOutput)
  }

  val sessionEndpoint: Endpoint[SecurityInput, Unit, SecurityError, AuthToken, Any] =
    register(endpoint
      .tag("auth").name("getSession").description("Get the current session")
      .get.in("api" / "auth" / "session")
      .securityIn(securityInput)
      .out(jsonBody[AuthToken])
      .errorOut(errorOutput))

  val refreshEndpoint: Endpoint[(Option[String], Option[String]), String, SecurityError, AuthCookies, Any] =
    register(endpoint
      .tag("auth").name("authRefreshTokens").description("Refresh the users auth tokens")
      .post.in("api" / "auth" / "refresh")
      .securityIn(xsrfSecurityInput)
      .in(cookie[String]("refresh_token"))
      .out(AuthCookies.endpointOutput)
      .errorOut(errorOutput))

  // Clears the local session and, when the provider has an end-session endpoint configured, returns
  // a URL the frontend navigates to so the upstream session is ended as well.
  val logoutEndpoint =
    register(endpoint
      .tag("auth").name("authLogout").description("Logout the current user")
      .post.in("api" / "auth" / "logout")
      .securityIn(xsrfSecurityInput)
      .in(cookie[Option[String]](providerIdTokenCookieName).description("The identity provider ID token, passed on as id_token_hint"))
      .in(requestOrigin)
      .errorOut(errorOutput)
      .out(jsonBody[LogoutResponse])
      .out(statusCode(StatusCode.Ok))
      .out(AuthCookies.endpointOutput))

  val oauth2loginEndpoint =
    register(endpoint.tag("auth").name("authLogin").description("Redirect to OAuth2 provider for login")
      .get.in("api" / "auth" / "login" / path[String]("provider"))
      .in(requestOrigin)
      .out(RedirectResponse.endpointOutput and setCookie("oauth_login_state"))
      .errorOut(ErrorResponse.endpointOutput))

  val oauth2CallbackEndpoint =
    register(endpoint.tag("auth").name("authCallback").description("OAuth2 callback endpoint")
      .get.in("api" / "auth" / "callback" / path[String]("provider"))
      .in(query[String]("code").description("The authorization code returned by the OAuth2 provider"))
      .in(query[String]("state").description("The state parameter returned by the OAuth2 provider"))
      .in(cookie[String]("oauth_login_state").description("The state cookie to prevent CSRF attacks"))
      .in(requestOrigin)
      .out(RedirectResponse.endpointOutput)
      .out(AuthCookies.endpointOutput)
      .errorOut(ErrorResponse.endpointOutput))

  val getIdentityProvidersEndpoint: Endpoint[Unit, Unit, Unit, List[IdentityProviderDto], Any] =
    register(endpoint
      .tag("auth").name("getIdentityProviders").description("Get the list of available identity providers")
      .get.in("api" / "auth" / "identity-providers")
      .out(jsonBody[List[IdentityProviderDto]]))

  def apply(loginService: FederatedLoginService, authConfig: AuthConfig)(
    using serverOptions: Http4sServerOptions[IO],
    apiSecurity: ApiSecurity
  ): HttpRoutes[IO] = {

    def mapAuthenticationErrorToResponse(error: AuthenticationError): ErrorResponse = error match
      case InvalidCredentials      => ErrorResponse.unauthorized(message = "Invalid credentials")
      case UnknownIdentityProvider => ErrorResponse.notFound()
      case UnknownError            => ErrorResponse.internalServerError(message = "An unknown error occurred")

    routes[IO](serverOptions) {
      serverLogic(endpoint = refreshEndpoint, authorize = apiSecurity.authorizeXsrf) { _ => refreshToken =>
        loginService.refresh(refreshToken).map:
          case Left(_)               => Left(SecurityError.Unauthorized)
          case Right(authentication) => Right(apiSecurity.createCookies(authentication))
      }

      serverLogic(endpoint = sessionEndpoint)(auth => _ => IO(Right(auth)))

      serverLogic(endpoint = logoutEndpoint, authorize = apiSecurity.authorizeXsrf) { _ => (providerIdTokenCookie, origin) =>
        val logoutUrl = providerIdTokenCookie.flatMap(ProviderIdToken.decode).flatMap(loginService.logoutUrl(_, origin))
        IO.pure(Right((LogoutResponse(logoutUrl), apiSecurity.createLogoutCookes)))
      }

      serverLogic(endpoint = oauth2loginEndpoint) { (provider, origin) =>
        loginService.identityProviders.get(provider) match
          case None                 => IO.pure(Left(ErrorResponse.notFound()))
          case Some(providerConfig) =>
            for
              state      <- loginService.createState(provider)
              params      = Map(
                              "client_id"     -> providerConfig.clientId,
                              "response_type" -> "code",
                              "redirect_uri"  -> origin.callbackUri(providerConfig.name),
                              "scope"         -> providerConfig.scopes.mkString(" "),
                              "state"         -> state
                            )
              redirectUri = providerConfig.authorizeUrl.addParams(params)
              stateCookie = CookieValueWithMeta.unsafeApply(
                              value    = state,
                              path     = Some("/"),
                              httpOnly = true,
                              secure   = authConfig.secureCookies,
                              sameSite = Some(SameSite.Lax),
                              expires  = Some(Instant.now().plus(authConfig.oauthStateExpiration.toJava))
                            )
            yield Right(RedirectResponse(redirectUri.toString) -> stateCookie)
      }

      serverLogicT(endpoint = oauth2CallbackEndpoint) {
        case (provider, code, state, clientState, origin) =>
          for
            _              <- EitherT.fromOption[IO](loginService.identityProviders.get(provider), ErrorResponse.notFound())
            _              <- EitherT.cond[IO](state == clientState, (), ErrorResponse.badRequest(message = "State mismatch"))
            authentication <- loginService.login(provider, code, clientState, origin).leftMap(mapAuthenticationErrorToResponse)
          yield RedirectResponse("/") -> apiSecurity.createCookies(authentication)
      }

      serverLogic(endpoint = getIdentityProvidersEndpoint) { _ =>
        IO.pure(Right(loginService.identityProviders.values.filterNot(_.adminOnly.getOrElse(false)).map { provider =>
          IdentityProviderDto(
            name     = provider.name,
            loginUrl = s"/api/auth/login/${provider.name}"
          )
        }.toList))
      }
    }
  }
