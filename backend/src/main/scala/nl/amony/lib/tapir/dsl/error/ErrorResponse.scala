package nl.amony.lib.tapir.dsl.error

import scala.reflect.ClassTag

import io.circe.Codec
import sttp.model.StatusCode
import sttp.tapir.EndpointOutput.OneOfVariant
import sttp.tapir.json.circe.*
import sttp.tapir.{EndpointOutput, Schema, oneOf, oneOfVariantClassMatcher, oneOfVariantValueMatcher, statusCode}

/**
 * Consolidated error DSL: gives every error a JSON body with a stable technical `code` and a human
 * `message`, while keeping each error variant as its own status code in the OpenAPI spec.
 */

/** Body sent with every error response. `code` is machine-readable and stable, `message` is for humans. */
case class ErrorBody(code: String, message: String) derives Schema, Codec

/** Contract every API error implements so it can describe its own HTTP representation. */
trait ApiErrorLike:
  def statusCode: StatusCode
  def code: String
  def message: String

/** Gathers the `oneOf` variants for a (possibly union) error set `E`. */
trait ErrorVariants[E]:
  def variants: List[OneOfVariant[? <: E]]

object ErrorVariants:

  def apply[E](using ev: ErrorVariants[E]): ErrorVariants[E] = ev

  /** One variant per enum value; each value's fixed status/code/message becomes a variant. */
  def fromValues[E <: ApiErrorLike](values: List[E]): ErrorVariants[E] =
    new ErrorVariants[E]:
      def variants: List[OneOfVariant[? <: E]] = values.map(errorVariant)

  /**
   * One variant for a whole error class, matched by runtime class. Unlike [[fromValues]] the body is
   * encoded per instance, so the server logic can pick `code`/`message` at runtime. Use one class per
   * status code where the body varies.
   */
  def single[E <: ApiErrorLike](status: StatusCode)(using ct: ClassTag[E]): ErrorVariants[E] =
    new ErrorVariants[E]:
      def variants: List[OneOfVariant[? <: E]] = List(bodyVariant[E](status))

  /** Combine two error sets into their union. */
  extension [A](a: ErrorVariants[A])
    def or[B](b: ErrorVariants[B]): ErrorVariants[A | B] =
      new ErrorVariants[A | B]:
        def variants: List[OneOfVariant[? <: A | B]] =
          (a.variants ++ b.variants).asInstanceOf[List[OneOfVariant[? <: A | B]]]

  private def errorVariant[E <: ApiErrorLike](error: E): OneOfVariant[E] =
    val body: ErrorBody           = ErrorBody(error.code, error.message)
    val output: EndpointOutput[E] =
      statusCode(error.statusCode).and(jsonBody[ErrorBody]).map[E](_ => error)(_ => body)
    oneOfVariantValueMatcher(output)(_ == error)

  /** Error responses are only ever encoded by the server; the inverse direction has no consumer. */
  private def notDecodable[E]: ErrorBody => E =
    _ => throw new UnsupportedOperationException("Cannot decode an error response: these endpoints are only used server-side")

  private def bodyVariant[E <: ApiErrorLike](status: StatusCode)(using ct: ClassTag[E]): OneOfVariant[E] =
    val output: EndpointOutput[E] =
      statusCode(status).and(jsonBody[ErrorBody]).map[E](notDecodable[E])(error => ErrorBody(error.code, error.message))
    oneOfVariantClassMatcher(output, ct.runtimeClass)

/** An error response definition for the error set `S`, e.g. `ErrorResponse[SecurityError | BadRequestError]`. */
final case class ErrorResponse[S](variants: List[OneOfVariant[? <: S]]):
  def output: EndpointOutput[S] = oneOf(variants.head, variants.tail*)

object ErrorResponse:

  import ErrorVariants.*

  /** The errors of a single set `S`, using its given [[ErrorVariants]] instance. */
  def of[S](using ev: ErrorVariants[S]): ErrorResponse[S] = ErrorResponse(ev.variants)

  /**
   * The union of two to four error sets, composed on the spot from their [[ErrorVariants]] instances so
   * that no union instance has to be declared by hand. E.g. `ErrorResponse.of[SecurityError, NotFoundError, BadRequestError]`.
   */
  def of[A, B](using ea: ErrorVariants[A], eb: ErrorVariants[B]): ErrorResponse[A | B] =
    ErrorResponse(ea.or(eb).variants)

  def of[A, B, C](using ea: ErrorVariants[A], eb: ErrorVariants[B], ec: ErrorVariants[C]): ErrorResponse[A | B | C] =
    ErrorResponse(ea.or(eb).or(ec).variants)

  def of[A, B, C, D](using ea: ErrorVariants[A], eb: ErrorVariants[B], ec: ErrorVariants[C], ed: ErrorVariants[D]): ErrorResponse[A | B | C | D] =
    ErrorResponse(ea.or(eb).or(ec).or(ed).variants)

  /** The 401/403 set for an endpoint secured with [[SecurityError]]. */
  val securityErrors: EndpointOutput[SecurityError] = ErrorResponse.of[SecurityError].output

  /** The 401/403, 404 and 400 set shared by the resource, collection and search endpoints. */
  val standardErrorOutput: EndpointOutput[SecurityError | NotFoundError | BadRequestError] =
    ErrorResponse.of[SecurityError, NotFoundError, BadRequestError].output
