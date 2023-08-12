package nl.amony.service.auth

import pdi.jwt.JwtAlgorithm
import pureconfig._
import pureconfig.generic.derivation.default._

import scala.concurrent.duration.FiniteDuration

case class AuthConfig(
    jwt: JwtConfig,
    adminUsername: String,
    adminPassword: String
) derives ConfigReader

case class JwtConfig(secretKey: String, tokenExpiration: FiniteDuration) derives ConfigReader {
  val algo = JwtAlgorithm.HS256
}
