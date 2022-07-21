package nl.amony.service.auth

import pdi.jwt.JwtAlgorithm

import scala.concurrent.duration.FiniteDuration

case class AuthConfig(
    jwt: JwtConfig,
    adminUsername: String,
    adminPassword: String
)

case class JwtConfig(secretKey: String, tokenExpiration: FiniteDuration) {
  val algo = JwtAlgorithm.HS256
}
