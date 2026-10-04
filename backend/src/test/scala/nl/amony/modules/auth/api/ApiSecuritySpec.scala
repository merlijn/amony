package nl.amony.modules.auth.api

import org.scalatest.wordspec.AnyWordSpecLike

class ApiSecuritySpec extends AnyWordSpecLike {

  "ApiSecurity.isAllowedHost" should {
    "accept a listed host" in
      assert(ApiSecurity.isAllowedHost(List("localhost:8182"), "localhost:8182"))
    "match case-insensitively" in
      assert(ApiSecurity.isAllowedHost(List("Demo.Amony.App"), "demo.amony.app"))
    "reject an unlisted host" in
      assert(!ApiSecurity.isAllowedHost(List("demo.amony.app"), "evil.example.com"))
    "reject an empty host" in
      assert(!ApiSecurity.isAllowedHost(List("demo.amony.app"), ""))
  }

  "ApiSecurity.canAccessBucket" should {

    val anonymous     = AuthToken.anonymous
    val authenticated = AuthToken(UserId("user-1"), Set(Role.Authenticated))
    val admin         = AuthToken(UserId("admin"), Set(Role.Admin, Role.Authenticated))

    "allow any token when the bucket requires no role" in {
      assert(ApiSecurity.canAccessBucket(anonymous, None, loginRequired = false))
      assert(ApiSecurity.canAccessBucket(authenticated, None, loginRequired = false))
    }

    "allow a token that holds the required role" in
      assert(ApiSecurity.canAccessBucket(authenticated, Some(Role.Authenticated), loginRequired = false))

    "deny a token that does not hold the required role" in
      assert(!ApiSecurity.canAccessBucket(anonymous, Some(Role.Authenticated), loginRequired = false))

    "deny anonymous access when login is required" in
      assert(!ApiSecurity.canAccessBucket(anonymous, None, loginRequired = true))

    "let admins bypass the required role" in
      assert(ApiSecurity.canAccessBucket(admin, Some(Role("private")), loginRequired = false))
  }
}
