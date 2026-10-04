package nl.amony.modules.auth.api

import scala.concurrent.duration.DurationInt

import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.auth.{AuthConfig, HS256Config, JwtConfig, RoleAccessConfig}

class ApiSecuritySpec extends AnyWordSpecLike {

  private def authConfig(requireLogin: Boolean = false) = AuthConfig(
    enabled           = true,
    requireLogin      = requireLogin,
    jwt               = JwtConfig(15.minutes, 7.days, HS256Config("test-secret-key")),
    secureCookies     = false,
    identityProviders = Nil,
    accessControl     = Map(
      Role.Anonymous     -> RoleAccessConfig(Set.empty),
      Role.Authenticated -> RoleAccessConfig(Set.empty)
    )
  )

  private val noLogin       = new ApiSecurity(authConfig())
  private val loginRequired = new ApiSecurity(authConfig(requireLogin = true))

  "ApiSecurity" when {

    "validating hosts" should {

      "accept a listed host" in
        assert(noLogin.isAllowedHost(List("localhost:8182"), "localhost:8182"))
      "match case-insensitively" in
        assert(noLogin.isAllowedHost(List("Demo.Amony.App"), "demo.amony.app"))
      "reject an unlisted host" in
        assert(!noLogin.isAllowedHost(List("demo.amony.app"), "evil.example.com"))
      "reject an empty host" in
        assert(!noLogin.isAllowedHost(List("demo.amony.app"), ""))
    }

    "validating bucket access" should {
      val anonymous     = AuthToken.anonymous
      val authenticated = AuthToken(UserId("user-1"), Set(Role.Authenticated))
      val admin         = AuthToken(UserId("admin"), Set(Role.Admin, Role.Authenticated))

      "allow any token when the bucket requires no role" in {
        assert(noLogin.canAccessBucket(anonymous, None))
        assert(noLogin.canAccessBucket(authenticated, None))
      }

      "allow a token that holds the required role" in
        assert(noLogin.canAccessBucket(authenticated, Some(Role.Authenticated)))

      "deny a token that does not hold the required role" in
        assert(!noLogin.canAccessBucket(anonymous, Some(Role.Authenticated)))

      "deny anonymous access when login is required" in
        assert(!loginRequired.canAccessBucket(anonymous, None))

      "let admins bypass the required role" in
        assert(noLogin.canAccessBucket(admin, Some(Role("private"))))
    }
  }
}
