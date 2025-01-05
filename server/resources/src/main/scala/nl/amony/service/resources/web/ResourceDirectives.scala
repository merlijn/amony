package nl.amony.service.resources.web

import cats.data.NonEmptyList
import cats.effect.{Async, IO}
import cats.implicits.{toFlatMapOps, toFunctorOps}
import fs2.Stream
import fs2.io.file.{Files, Path}
import nl.amony.service.resources.{Resource, ResourceWithRangeSupport}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Range.SubRange
import org.http4s.headers.{
  Range,
  `Accept-Encoding`,
  `Accept-Ranges`,
  `Content-Encoding`,
  `Content-Range`,
  `Content-Type`
}
import org.typelevel.ci.CIStringSyntax
import scribe.Logging

object ResourceDirectives extends Logging {

  def respondWithResourceContent(req: Request[IO], resource: Resource) = {
    resource match {
      case resource: ResourceWithRangeSupport =>

        val maybeMediaType = resource.contentType().map(MediaType.parse(_).toOption).flatten
        val additionalHeaders = Headers(maybeMediaType.map(mediaType => `Content-Type`(mediaType)).toList)

        ResourceDirectives.responseWithRangeSupport[IO](
          request = req,
          size = resource.size(),
          rangeResponseFn = resource.getContentRange,
          additionalHeaders = additionalHeaders
        )
      case _ =>
        val maybeMediaType = resource.contentType().map(MediaType.parse(_).toOption).flatten.map(`Content-Type`.apply)

        Response(
          status = Status.Ok,
          headers = maybeMediaType.map(mediaType => Headers(mediaType)).getOrElse(Headers.empty),
          body = resource.getContent()
        )

        Ok(resource.getContent())
    }
  }

  val AcceptRangeHeader = `Accept-Ranges`(RangeUnit.Bytes)

  private def isValidRange(start: Long, end: Option[Long], fileLength: Long): Boolean =
    start < fileLength && (end match {
      case Some(end) => start >= 0 && start <= end
      case None      => start >= 0 || fileLength + start - 1 >= 0
    })

  def fromPath[F[_]](req: Request[F], path: Path, chunkSize: Int, additionalHeaders: Headers = Headers.empty, detectMediaType: Boolean = true, useCompression: Boolean = true)(using F: Async[F]): F[Response[F]] = {
    Files[F].getBasicFileAttributes(path).flatMap { fileAttributes =>

      val mediaTypeHeaders = {
        if (detectMediaType)
          val maybeMediaType = Option.when(path.extName.nonEmpty)(path.extName.substring(1)).flatMap(MediaType.forExtension)
          Headers(maybeMediaType.map(mediaType => `Content-Type`(mediaType)).toList)
        else
          Headers.empty
      }

      def respondWithoutCompression() =
        responseWithRangeSupport(
          req,
          fileAttributes.size,
          mediaTypeHeaders ++ additionalHeaders,
          (start, end) => Files[F].readRange(path, chunkSize, start, end)
        )

      def supportsBrEncoding = req.headers.get[`Accept-Encoding`].map(_.values.exists(_.coding == "br")).getOrElse(false)

      if (useCompression && supportsBrEncoding) {
        val brPath = path.resolveSibling(path.fileName.toString + ".br")
        Files[F].exists(brPath).flatMap:
          case true =>
            fromPath(
              req,
              brPath,
              chunkSize,
              additionalHeaders ++ mediaTypeHeaders ++ Headers(`Content-Encoding`(ContentCoding.br)),
              detectMediaType = false,
              useCompression = false
            )
          case false =>
            respondWithoutCompression()
      }
      else
        respondWithoutCompression()
    }
  }

  // Attempt to find a Range header and collect only the subrange of content requested
  def responseWithRangeSupport[F[_]](
      request: Request[F],
      size: Long,
      additionalHeaders: Headers,
      rangeResponseFn: (Long, Long) => Stream[F, Byte])(using F: Async[F]): F[Response[F]] = {

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
          Headers(Header.Raw(ci"Content-Length", size.toString)) ++ additionalHeaders

        Response(
          status = if (partial) Status.PartialContent else Status.Ok,
          headers = headers,
          body = byteStream
        )
      }
    }

    request.headers.get[Range] match {
      case Some(Range(RangeUnit.Bytes, NonEmptyList(SubRange(s, e), Nil))) =>
        if (isValidRange(s, e, size)) {
          val start = if (s >= 0) s else math.max(0, size + s)
          val end = math.min(size - 1, e.getOrElse(size - 1)) // end is inclusive
          createResponse(start, end, true)
        } else {
          rangeNotSatisfiableResponse
        }
      case _ =>
        request.headers.get(ci"Range") match {
          case Some(_) => rangeNotSatisfiableResponse
          case None    => createResponse(0, size - 1, false)
        }
    }
  }
}
