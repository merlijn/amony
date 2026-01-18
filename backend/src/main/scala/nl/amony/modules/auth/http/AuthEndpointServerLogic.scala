package nl.amony.modules.auth.http

import java.time.{Duration, Instant}
import java.util.UUID

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.toSemigroupKOps
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import scribe.Logging
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

import nl.amony.lib.tapir.*
import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.auth.api.*
import nl.amony.modules.auth.http.AuthEndpointDefs.*

object AuthEndpointServerLogic extends Logging {

  def apply(authService: AuthService, authConfig: AuthConfig, apiSecurity: ApiSecurity)(
    using serverOptions: Http4sServerOptions[IO]
  ): HttpRoutes[IO] = {

    val sessionImpl = sessionEndpoint
      .serverSecurityLogicPure(i => apiSecurity.requireSession(i, xsrfProtection = false))
      .serverLogic(auth => _ => IO(Right(auth)))

    val loginImpl = loginEndpoint.serverLogic: credentials =>
      authService.authenticate(credentials.username, credentials.password).map:
        case Left(_)               => Left(SecurityError.Unauthorized)
        case Right(authentication) => Right(RedirectResponse("/") -> apiSecurity.createCookies(authentication))

    val refreshImpl = refreshEndpoint.serverLogic: refreshToken =>
      authService.refresh("", refreshToken).map:
        case Left(_)               => Left(SecurityError.Unauthorized)
        case Right(authentication) => Right(apiSecurity.createCookies(authentication))

    val logoutImpl = logoutEndpoint.serverLogicSuccessPure[IO](_ => apiSecurity.createLogoutCookes)

    val oauthLoginLogic = oauth2loginEndpoint.serverLogicPure[IO] {
      provider =>
        for
          providerConfig <- authService.oauthProviders.get(provider).toRight(ErrorResponse.notFound())
          state           = UUID.randomUUID().toString
          params          = Map(
                              "client_id"     -> providerConfig.clientId,
                              "response_type" -> "code",
                              "redirect_uri"  -> authConfig.publicUri.addPath("api", "oauth", "callback", providerConfig.name).toString,
                              "scope"         -> providerConfig.scopes.mkString(" "),
                              "state"         -> state
                            )
          redirectUri     = providerConfig.authorizeUri.addParams(params)
          stateCookie     = CookieValueWithMeta.unsafeApply(
                              value    = state,
                              path     = Some("/"),
                              httpOnly = true,
                              secure   = false,
                              expires  = Some(Instant.now().plus(Duration.ofSeconds(300)))
                            )
        yield RedirectResponse(redirectUri.toString) -> stateCookie
    }

    val oauth2CallbackLogic = oauth2CallbackEndpoint.serverLogic[IO] {
      case (provider, code, state, clientState) =>

        val result =
          for
            _              <- EitherT.fromOption[IO](authService.oauthProviders.get(provider), ErrorResponse.notFound())
            _              <- EitherT.cond[IO](state == clientState, (), ErrorResponse.badRequest(message = "Invalid state parameter"))
            authentication <- EitherT(authService.authenticate(OauthToken(provider, code)).map {
                                case Right(authentication) => Right(authentication)
                                case Left(_)               => Left(ErrorResponse.unauthorized(message = "Invalid credentials"))
                              })
          yield RedirectResponse("/") -> apiSecurity.createCookies(authentication)

        result.value
    }

    Http4sServerInterpreter[IO](serverOptions).toRoutes(List(loginImpl, refreshImpl, sessionImpl, logoutImpl, oauthLoginLogic, oauth2CallbackLogic))
      <+> loginPage(authService, authConfig)
  }

  private def loginPage(authService: AuthService, authConfig: AuthConfig) = HttpRoutes.of[IO]:
    case GET -> Root / "login" => Ok(fs2.io.readClassLoaderResource[IO]("login.html"))
}
