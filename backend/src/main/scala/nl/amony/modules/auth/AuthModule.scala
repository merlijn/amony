package nl.amony.modules.auth

import cats.effect.IO
import scribe.Logging
import sttp.client4.Backend
import sttp.tapir.server.http4s.Http4sServerOptions

import nl.amony.modules.auth.api.{ApiSecurity, AuthService, SecurityError}
import nl.amony.modules.auth.http.AuthEndpointServerLogic

class AuthModule(config: AuthConfig, httpClientBackend: Backend[IO]) extends Logging {

  val authService = new AuthService(config, httpClientBackend)
  val apiSecurity = new ApiSecurity(config)

  logger.info("AuthModule initialized, oauth providers: " + authService.oauthProviders.keys.mkString(", "))

  def routes(using serverOptions: Http4sServerOptions[IO]) = AuthEndpointServerLogic.apply(authService, config, apiSecurity)
}
