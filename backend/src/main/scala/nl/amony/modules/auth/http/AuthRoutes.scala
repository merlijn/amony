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

case class OAuthProviderDto(name: String, loginUrl: String) derives Codec, Schema

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

  val logoutEndpoint: Endpoint[(Option[String], Option[String]), Unit, SecurityError, AuthCookies, Any] =
    register(endpoint
      .tag("auth").name("authLogout").description("Logout the current user")
      .post.in("api" / "auth" / "logout")
      .securityIn(xsrfSecurityInput)
      .errorOut(errorOutput)
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

  val getOAuthProvidersEndpoint: Endpoint[Unit, Unit, Unit, List[OAuthProviderDto], Any] =
    register(endpoint
      .tag("auth").name("getOAuthProviders").description("Get the list of available OAuth providers")
      .get.in("api" / "auth" / "oauth-providers")
      .out(jsonBody[List[OAuthProviderDto]]))

  def apply(authService: AuthService, authConfig: AuthConfig)(
    using serverOptions: Http4sServerOptions[IO],
    apiSecurity: ApiSecurity
  ): HttpRoutes[IO] = {

    def mapAuthenticationErrorToResponse(error: AuthenticationError): ErrorResponse = error match
      case InvalidCredentials   => ErrorResponse.unauthorized(message = "Invalid credentials")
      case UnknownOAuthProvider => ErrorResponse.notFound()
      case UnknownError         => ErrorResponse.internalServerError(message = "An unknown error occurred")

    routes[IO](serverOptions) {
      serverLogic(endpoint = refreshEndpoint, authorize = apiSecurity.authorizeXsrf) { _ => refreshToken =>
        authService.refresh(refreshToken).map:
          case Left(_)               => Left(SecurityError.Unauthorized)
          case Right(authentication) => Right(apiSecurity.createCookies(authentication))
      }

      serverLogic(endpoint = sessionEndpoint)(auth => _ => IO(Right(auth)))

      serverLogic(endpoint = logoutEndpoint, authorize = apiSecurity.authorizeXsrf) { _ => _ =>
        IO.pure(Right(apiSecurity.createLogoutCookes))
      }

      serverLogic(endpoint = oauth2loginEndpoint) { (provider, origin) =>
        authService.oauthProviders.get(provider) match
          case None                 => IO.pure(Left(ErrorResponse.notFound()))
          case Some(providerConfig) =>
            for
              state      <- authService.createState(provider)
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
            _              <- EitherT.fromOption[IO](authService.oauthProviders.get(provider), ErrorResponse.notFound())
            _              <- EitherT.cond[IO](state == clientState, (), ErrorResponse.badRequest(message = "State mismatch"))
            _              <- authService.validateAndConsumeState(provider, clientState).leftMap(mapAuthenticationErrorToResponse)
            authentication <- authService.authenticate(OauthTokenCredentials(provider, code), origin).leftMap(mapAuthenticationErrorToResponse)
          yield RedirectResponse("/") -> apiSecurity.createCookies(authentication)
      }

      serverLogic(endpoint = getOAuthProvidersEndpoint) { _ =>
        IO.pure(Right(authService.oauthProviders.values.filterNot(_.adminOnly.getOrElse(false)).map { provider =>
          OAuthProviderDto(
            name     = provider.name,
            loginUrl = s"/api/auth/login/${provider.name}"
          )
        }.toList))
      }
    }
  }
