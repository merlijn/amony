package nl.amony.service.auth

import cats.effect.IO
import nl.amony.service.auth.Authenticator.validateSecurityInput
import nl.amony.service.auth.tapir.SecurityInput

enum SecurityError:
  case Unauthorized
  case Forbidden

object Authenticator:

  def validateSecurityInput(decoder: JwtDecoder, securityInput: SecurityInput, requiredRole: Role): Either[SecurityError, AuthToken] = {
    for {
      accessToken <- securityInput.accessToken.toRight(SecurityError.Unauthorized)
      decoded     <- decoder.decode(accessToken).toEither.left.map(_ => SecurityError.Unauthorized)
      xsrfToken   <- securityInput.xsrfCookie.toRight(SecurityError.Unauthorized)
      xXsrfHeader <- securityInput.xXsrfHeader.toRight(SecurityError.Unauthorized)
      _           <- if xsrfToken == xXsrfHeader then Right(()) else Left(SecurityError.Unauthorized)
    } yield AuthToken(decoded.userId, decoded.roles)
  }

class Authenticator(decoder: JwtDecoder):

  def publicEndpoint(securityInput: SecurityInput): IO[Either[SecurityError, AuthToken]] =
    IO.pure(Right(AuthToken.anonymous))

  def requireRole(requiredRole: Role)(securityInput: SecurityInput): IO[Either[SecurityError, AuthToken]] =
    IO.pure(
      validateSecurityInput(decoder, securityInput, requiredRole).flatMap { token =>
        if token.roles.contains(requiredRole) then Right(token)
        else Left(SecurityError.Forbidden)
      }
    )