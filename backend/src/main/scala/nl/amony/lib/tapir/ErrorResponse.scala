package nl.amony.lib.tapir

import io.circe.Codec
import sttp.model.StatusCode
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.{EndpointOutput, Schema, statusCode}

case class ErrorResponseBody(code: String, message: String) derives Schema, Codec
case class ErrorResponse(statusCode: StatusCode, body: ErrorResponseBody)

object ErrorResponse:

  val endpointOutput: EndpointOutput[ErrorResponse] = (statusCode and jsonBody[ErrorResponseBody]).mapTo[ErrorResponse]

  def notFound(code: String = "not_found", message: String = "Resource not found"): ErrorResponse =
    ErrorResponse(StatusCode.NotFound, ErrorResponseBody(code, message))

  def badRequest(code: String = "bad_request", message: String = "Invalid request"): ErrorResponse =
    ErrorResponse(StatusCode.BadRequest, ErrorResponseBody(code, message))

  def unauthorized(code: String = "unauthorized", message: String = "Unauthorized"): ErrorResponse =
    ErrorResponse(StatusCode.Unauthorized, ErrorResponseBody(code, message))

  def forbidden(code: String = "forbidden", message: String = "Forbidden"): ErrorResponse =
    ErrorResponse(StatusCode.Forbidden, ErrorResponseBody(code, message))
