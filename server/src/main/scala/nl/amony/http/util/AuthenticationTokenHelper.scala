package nl.amony.http.util

import pdi.jwt.JwtClaim

import java.time.Instant
import io.circe._
import jawn.{parse => jawnParse}
import pdi.jwt.{JwtAlgorithm, JwtCirce}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

case class AuthConfig(
   secretKey: String,
   algo: String,
   tokenExpiration: FiniteDuration)

class AuthenticationTokenHelper(config: AuthConfig) {

  val expirationInSeconds = config.tokenExpiration.toSeconds
  val algo = JwtAlgorithm.HS256 // TODO get from config

  def createToken(userId: String): String = {

    val claim = JwtClaim(
      expiration = Some(Instant.now.plusSeconds(expirationInSeconds).getEpochSecond),
      issuedAt = Some(Instant.now.getEpochSecond)
    ) + ("admin", true) + ("userId", userId)

    val token = JwtCirce.encode(claim, config.secretKey, algo)

    token
  }

  def decodeToken(token: String): Try[JwtClaim] = {
    JwtCirce.decode(token, config.secretKey, Seq(algo))
  }
}
