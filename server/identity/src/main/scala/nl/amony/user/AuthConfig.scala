package nl.amony.user

import scala.concurrent.duration.FiniteDuration

case class AuthConfig(
   secretKey: String,
   algo: String,
   tokenExpiration: FiniteDuration)
