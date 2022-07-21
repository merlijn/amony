package nl.amony.service.auth.actor

import nl.amony.service.auth.JwtConfig
import pdi.jwt.{JwtCirce, JwtClaim}

import java.time.Instant

class TokenManager(jwtConfig: JwtConfig) {

  val expirationInSeconds = jwtConfig.tokenExpiration.toSeconds

  def createToken(userId: String): String = {

    val claim = JwtClaim(
      expiration = Some(Instant.now.plusSeconds(expirationInSeconds).getEpochSecond),
      issuedAt   = Some(Instant.now.getEpochSecond)
    ) + ("userId", userId)

    val token = JwtCirce.encode(claim, jwtConfig.secretKey, jwtConfig.algo)

    token
  }
}
