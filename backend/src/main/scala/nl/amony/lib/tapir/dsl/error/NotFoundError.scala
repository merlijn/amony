package nl.amony.lib.tapir.dsl.error

import sttp.model.StatusCode

/**
 * Standard 404 error. Like [[BadRequestError]] the `code` and `message` are decided by the server logic,
 * so every "not found" cause shares one variant (and the [[ErrorBody]] JSON shape). For
 * security-sensitive resources an opaque error may be preferable, so that absence is not revealed.
 */
final case class NotFoundError(code: String, message: String) extends ApiErrorLike:
  def statusCode: StatusCode = StatusCode.NotFound

object NotFoundError:
  given ErrorVariants[NotFoundError] = ErrorVariants.single[NotFoundError](StatusCode.NotFound)
