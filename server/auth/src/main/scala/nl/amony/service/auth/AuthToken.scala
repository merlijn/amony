package nl.amony.service.auth

import io.circe.Codec
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.customise

def required[T](s: Schema[T]) = s.copy(isOptional = false)

case class AuthToken(
  userId: String,
  @customise(required)
  roles: Set[Role],
) derives Codec, sttp.tapir.Schema

object AuthToken {
  val anonymous: AuthToken = AuthToken(
    userId = "anonymous",
    roles = Set.empty
  )
}
