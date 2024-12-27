package nl.amony.service.auth

import io.circe.*
import pdi.jwt.{JwtCirce, JwtClaim, JwtUtils}

import java.time.Instant

case class AuthTokenContent(
  roles: Set[String]
) derives Decoder

class JwtDecoder(algo: JwtAlgorithmConfig):
  def decode(token: String): scala.util.Try[AuthToken] = {
    for {
      decoded <- algo.decode(token)
      content <- parser.decode[AuthTokenContent](decoded.content).toTry
    } yield AuthToken(decoded.subject.getOrElse(""), content.roles.map(Role.apply))
  }

trait JwtEncoder:
  def encode(claim: JwtClaim): String


class TokenManager(jwtConfig: JwtConfig) {

  private val expirationInSeconds = jwtConfig.accessTokenExpiration.toSeconds
  
  def refreshAccessToken(refreshToken: String): Option[(String, String)] = {
    jwtConfig.algorithm.decode(refreshToken) match {
      case scala.util.Success(token) =>
        Some(createAccessAndRefreshTokens(token.subject, token.content))
      case scala.util.Failure(_)     => 
        None
    }
  }

  def createAccessAndRefreshTokens(maybeSubject: Option[String], roles: Set[String]): (String, String) = {
    val content = JwtUtils.mergeJson("{}", JwtUtils.hashToJson(Seq("roles" -> roles)))
    createAccessAndRefreshTokens(maybeSubject, content)
  }

  def createAccessAndRefreshTokens(maybeSubject: Option[String], content: String = "{}"): (String, String) = {

    val now = Instant.now
    
    val accessTokenClaim = JwtClaim(
      expiration = Some(now.plusSeconds(expirationInSeconds).getEpochSecond),
      issuedAt   = Some(now.getEpochSecond),
      notBefore  = Some(now.getEpochSecond),
      subject    = maybeSubject,
      content    = content
    )

    val refreshTokenClaim = JwtClaim(
      expiration = Some(now.plusSeconds(jwtConfig.refreshTokenExpiration.toSeconds).getEpochSecond),
      issuedAt   = Some(now.getEpochSecond),
      notBefore  = Some(now.getEpochSecond),
      subject    = maybeSubject,
      content    = content
    )

    (jwtConfig.algorithm.encode(accessTokenClaim), jwtConfig.algorithm.encode(refreshTokenClaim))
  }
}
