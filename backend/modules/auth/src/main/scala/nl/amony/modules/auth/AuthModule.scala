package nl.amony.modules.auth

import cats.effect.{IO, Resource}
import scribe.Logging
import skunk.Session
import sttp.client4.Backend

import nl.amony.modules.auth.api.{ApiSecurity, FederatedLoginService}
import nl.amony.modules.auth.http.AuthRoutes

class AuthModule(config: AuthConfig, httpClientBackend: Backend[IO], pool: Resource[IO, Session[IO]]) extends Logging {

  private val userDatabase       = new dal.UserDatabase(pool)
  private val oauthStateDatabase = new dal.OAuthStateDatabase(pool)
  private val loginService       = new FederatedLoginService(config, httpClientBackend, userDatabase, oauthStateDatabase)
  val apiSecurity                = new ApiSecurity(config)

  logger.info("AuthModule initialized, identity providers: " + loginService.identityProviders.keys.mkString(", "))

  def routes(using apiSecurity: ApiSecurity) = AuthRoutes.apply(loginService, config)
}
