package nl.amony.service.auth

import pdi.jwt.{JwtCirce, JwtClaim}

import java.time.Instant

class TokenManager(jwtConfig: JwtConfig) {

  private val expirationInSeconds = jwtConfig.accessTokenExpiration.toSeconds

  def createJwtToken(userId: String, roles: Set[String]): String = {

    val claim = JwtClaim(
      expiration = Some(Instant.now.plusSeconds(expirationInSeconds).getEpochSecond),
      issuedAt   = Some(Instant.now.getEpochSecond),
      notBefore  = Some(Instant.now.getEpochSecond),
    ) + ("userId", userId) + ("roles", roles)

    val token = jwtConfig.algorithm.encode(claim)

    token
  }
}
