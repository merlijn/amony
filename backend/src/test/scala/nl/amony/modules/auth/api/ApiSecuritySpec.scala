package nl.amony.modules.auth.api

import org.scalatest.wordspec.AnyWordSpecLike

class ApiSecuritySpec extends AnyWordSpecLike {

  private val noLogin       = TestApiSecurity()
  private val loginRequired = TestApiSecurity(requireLogin = true)

  "ApiSecurity.isAllowedHost" should {
    "accept a listed host" in
      assert(noLogin.isAllowedHost(List("localhost:8182"), "localhost:8182"))
    "match case-insensitively" in
      assert(noLogin.isAllowedHost(List("Demo.Amony.App"), "demo.amony.app"))
    "reject an unlisted host" in
      assert(!noLogin.isAllowedHost(List("demo.amony.app"), "evil.example.com"))
    "reject an empty host" in
      assert(!noLogin.isAllowedHost(List("demo.amony.app"), ""))
  }

  "ApiSecurity.canAccessBucket" should {

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
