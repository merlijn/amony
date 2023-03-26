package nl.amony.service.resources

import cats.data.{NonEmptyList, OptionT}
import cats.effect.Async
import cats.implicits.{toFlatMapOps, toFunctorOps, toUnorderedFoldableOps}
import fs2.Stream
import fs2.io.file.{Files, Path}
import org.http4s._
import org.http4s.headers.Range.SubRange
import org.http4s.headers.{Range, `Accept-Ranges`, `Content-Range`, `Content-Type`}
import org.typelevel.ci.CIStringSyntax
import scribe.Logging

object ResourceDirectives extends Logging {

  val AcceptRangeHeader = `Accept-Ranges`(RangeUnit.Bytes)

  private def validRange(start: Long, end: Option[Long], fileLength: Long): Boolean =
    start < fileLength && (end match {
      case Some(end) => start >= 0 && start <= end
      case None      => start >= 0 || fileLength + start - 1 >= 0
    })

  def fromPath[F[_]](req: Request[F], path: Path, chunkSize: Int)(implicit F: Async[F]): F[Response[F]] = {
    Files[F].getBasicFileAttributes(path).flatMap { attr =>

      val mediaType =
        Option.when(path.extName.nonEmpty)(path.extName.substring(1))
          .flatMap(MediaType.forExtension)

      responseWithRangeSupport(
        req, attr.size,
        mediaType,
        (start, end) => Files[F].readRange(path, chunkSize, start, end))
    }
  }

  // Attempt to find a Range header and collect only the subrange of content requested
  def responseWithRangeSupport[F[_]](req: Request[F], size: Long, maybeMediaType: Option[MediaType], rangeResponseFn: (Long, Long) => Stream[F, Byte])(implicit F: Async[F]): F[Response[F]] = {

    val rangeNotSatisfiableResponse: F[Response[F]] =
      F.pure {
        Response[F](
          status = Status.RangeNotSatisfiable,
          headers = Headers.apply(AcceptRangeHeader, `Content-Range`(SubRange(0, size - 1), Some(size))),
        )
      }

    def createResponse(start: Long, end: Long, partial: Boolean) = {
      F.pure(rangeResponseFn(start, end + 1)).map { byteStream =>

        val headers =
          Headers(AcceptRangeHeader, `Content-Range`(SubRange(start, end), Some(size))) ++
          Headers(maybeMediaType.map(mediaType => `Content-Type`(mediaType)).toList)

        Response(
          status = if (partial) Status.PartialContent else Status.Ok,
          headers = headers,
          entity = Entity(byteStream, Some(start - end))
        )
      }
    }

    req.headers.get[Range] match {
      case Some(Range(RangeUnit.Bytes, NonEmptyList(SubRange(s, e), Nil))) =>
        if (validRange(s, e, size)) {
          val start = if (s >= 0) s else math.max(0, size + s)
          val end = math.min(size - 1, e.getOrElse(size - 1)) // end is inclusive
          createResponse(start, end, true)
        } else {
          rangeNotSatisfiableResponse
        }
      case _ =>
        req.headers.get(ci"Range") match {
          case Some(_) => rangeNotSatisfiableResponse
          case None    => createResponse(0, size - 1, false)
        }
    }
  }
}
