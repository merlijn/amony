package nl.amony.modules.auth.api

import java.time.Instant
import scala.util.{Failure, Try}

import io.circe
import io.circe.*
import pdi.jwt.JwtClaim
import scribe.Logging

import nl.amony.modules.auth.{JwtAlgorithmConfig, JwtConfig}

case class AuthTokenContent(roles: Set[Role]) derives Codec

class JwtDecoder(algo: JwtAlgorithmConfig) extends Logging:
  def decode(token: String): Either[Throwable, AuthToken] = {
    for
      decoded <- algo.decode(token).toEither
      content <- parser.decode[AuthTokenContent](decoded.content)
      subject <- decoded.subject.toRight(new IllegalStateException("Token subject is missing"))
    yield AuthToken(UserId(subject), content.roles)
  }

class TokenManager(jwtConfig: JwtConfig) {

  private val expirationInSeconds = jwtConfig.accessTokenExpiration.toSeconds

  def refreshAccessToken(refreshToken: String): Option[Authentication] = {
    jwtConfig.algorithm.decode(refreshToken) match {
      case scala.util.Success(token) => Some(createAccessAndRefreshTokens(token.subject, token.content))
      case scala.util.Failure(_)     => None
    }
  }

  def createAccessAndRefreshTokens(maybeSubject: Option[String], roles: Set[Role]): Authentication = {
    val content = Encoder[AuthTokenContent].apply(AuthTokenContent(roles)).noSpaces

    createAccessAndRefreshTokens(maybeSubject, content)
  }

  private def createAccessAndRefreshTokens(maybeSubject: Option[String], content: String = "{}"): Authentication = {

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

    Authentication(jwtConfig.algorithm.encode(accessTokenClaim), jwtConfig.algorithm.encode(refreshTokenClaim))
  }
}
