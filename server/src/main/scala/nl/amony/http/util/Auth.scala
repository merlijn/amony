package nl.amony.http.util

import pdi.jwt.JwtClaim
import java.time.Instant
import io.circe._, jawn.{parse => jawnParse}
import pdi.jwt.{JwtCirce, JwtAlgorithm}
import scala.util.Try

object Auth {

  val key = "secretKey"
  val expirationInSeconds = 157784760
  val algo = JwtAlgorithm.HS256

  def createToken() = {

    val claim = JwtClaim(
      expiration = Some(Instant.now.plusSeconds(expirationInSeconds).getEpochSecond), 
      issuedAt = Some(Instant.now.getEpochSecond)
    )

    val token = JwtCirce.encode(claim, key, algo)

    token
  }

  def decodeToken(token: String): Try[JwtClaim] = {
    JwtCirce.decode(token, key, Seq(JwtAlgorithm.HS256))
  }
}
