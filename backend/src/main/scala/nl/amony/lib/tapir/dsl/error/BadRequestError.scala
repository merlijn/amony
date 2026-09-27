package nl.amony.lib.tapir.dsl.error

import sttp.model.StatusCode

/**
 * Standard 400 error: a bad request whose `code` and `message` are decided by the server logic at
 * runtime. All 400 causes share this single variant (and the [[ErrorBody]] JSON shape). Include it in an
 * endpoint's error union, e.g. `ErrorResponse[SecurityError | BadRequestError]`, to advertise a 400.
 */
final case class BadRequestError(code: String, message: String) extends ApiErrorLike:
  def statusCode: StatusCode = StatusCode.BadRequest

object BadRequestError:
  given FromBody[BadRequestError] = body => BadRequestError(body.code, body.message)

  given ErrorVariants[BadRequestError] = ErrorVariants.single[BadRequestError](StatusCode.BadRequest)
