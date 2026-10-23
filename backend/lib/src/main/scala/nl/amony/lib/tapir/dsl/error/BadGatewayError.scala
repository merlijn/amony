package nl.amony.lib.tapir.dsl.error

import sttp.model.StatusCode

/**
 * Standard 502 error: the server, acting as a gateway, received an invalid or unusable response from an
 * upstream service. Like [[BadRequestError]] the `code` and `message` are decided by the server logic.
 */
final case class BadGatewayError(code: String, message: String) extends ApiErrorLike:
  def statusCode: StatusCode = StatusCode.BadGateway

object BadGatewayError:
  given ErrorVariants[BadGatewayError] = ErrorVariants.single[BadGatewayError](StatusCode.BadGateway)
