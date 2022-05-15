package nl.amony.user

import scala.concurrent.duration.FiniteDuration

case class AuthConfig(
   jwt: JwtConfig,
   adminUsername: String,
   adminPassword: String,
)

case class JwtConfig(
    secretKey: String,
    algo: String,
    tokenExpiration: FiniteDuration)
