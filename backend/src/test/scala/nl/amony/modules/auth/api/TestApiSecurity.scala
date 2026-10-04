package nl.amony.modules.auth.api

import scala.concurrent.duration.*

import nl.amony.modules.auth.{AuthConfig, HS256Config, JwtConfig, RoleAccessConfig}

/** Minimal `ApiSecurity` instance for tests that need one but do not exercise a specific access policy. */
object TestApiSecurity:

  def apply(requireLogin: Boolean = false): ApiSecurity =
    new ApiSecurity(AuthConfig(
      enabled           = true,
      requireLogin      = requireLogin,
      jwt               = JwtConfig(15.minutes, 7.days, HS256Config("test-secret-key")),
      secureCookies     = false,
      identityProviders = Nil,
      accessControl     = Map(
        Role.Anonymous     -> RoleAccessConfig(Set.empty),
        Role.Authenticated -> RoleAccessConfig(Set.empty)
      )
    ))