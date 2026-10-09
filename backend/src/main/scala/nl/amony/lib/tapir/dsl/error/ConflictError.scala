package nl.amony.lib.tapir.dsl.error

import sttp.model.StatusCode

/**
 * Standard 409 error: the request conflicts with the current state of the target resource. Like
 * [[BadRequestError]] the `code` and `message` are decided by the server logic at runtime.
 */
final case class ConflictError(code: String, message: String) extends ApiErrorLike:
  def statusCode: StatusCode = StatusCode.Conflict

object ConflictError:
  given ErrorVariants[ConflictError] = ErrorVariants.single[ConflictError](StatusCode.Conflict)
