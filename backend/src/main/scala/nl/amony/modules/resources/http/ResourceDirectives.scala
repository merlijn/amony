package nl.amony.modules.resources.http

import cats.data.NonEmptyList
import cats.effect.{Async, IO}
import cats.implicits.{toFlatMapOps, toFunctorOps}
import fs2.Stream
import fs2.io.file.{Files, Path}
import org.http4s.*
import org.http4s.headers.Range.SubRange
import org.http4s.headers.{Range, `Accept-Encoding`, `Accept-Ranges`, `Content-Encoding`, `Content-Length`, `Content-Range`, `Content-Type`}
import org.typelevel.ci.CIStringSyntax
import scribe.Logging

import nl.amony.modules.resources.api.{Resource, ResourceContent, ResourceContentWithRangeSupport}

object ResourceDirectives extends Logging {

  def resourceContentsResponse(req: Request[IO], resource: ResourceContent): IO[Response[IO]] = {

    val maybeMediaType    = MediaType.parse(resource.contentType).toOption
    val additionalHeaders = Headers(maybeMediaType.map(mediaType => `Content-Type`(mediaType)).toList)

    resource match {
      case resource: ResourceContentWithRangeSupport =>
        ResourceDirectives.responseWithRangeSupport[IO](
          request           = req,
          size              = resource.size,
          additionalHeaders = additionalHeaders,
          rangeResponseFn   = resource.streamRange
        )
      case _                                         =>
        val response = Response(status = Status.Ok, headers = additionalHeaders, body = resource.stream)
        IO.pure(response)
    }
  }

  def responseFromFile[F[_]](
    req: Request[F],
    path: Path,
    chunkSize: Int,
    additionalHeaders: Headers = Headers.empty,
    detectMediaType: Boolean   = true,
    useCompression: Boolean    = true
  )(using F: Async[F]): F[Response[F]] = {
    Files[F].getBasicFileAttributes(path).flatMap {
      fileAttributes =>

        val mediaTypeHeaders = {
          if detectMediaType then
            val maybeMediaType = Option.when(path.extName.nonEmpty)(path.extName.substring(1)).flatMap(MediaType.forExtension)
            Headers(maybeMediaType.map(mediaType => `Content-Type`(mediaType)).toList)
          else Headers.empty
        }

        def responseWithoutCompression =
          responseWithRangeSupport(
            req,
            fileAttributes.size,
            mediaTypeHeaders ++ additionalHeaders,
            (start, end) => Files[F].readRange(path, chunkSize, start, end)
          )

        def supportsBrEncoding = req.headers.get[`Accept-Encoding`].map(_.values.exists(_.coding == "br")).getOrElse(false)

        if useCompression && supportsBrEncoding then {
          val brPath = path.resolveSibling(path.fileName.toString + ".br")
          Files[F].exists(brPath).flatMap:
            case true  => responseFromFile(
                req,
                brPath,
                chunkSize,
                additionalHeaders ++ mediaTypeHeaders ++ Headers(`Content-Encoding`(ContentCoding.br)),
                detectMediaType = false,
                useCompression  = false
              )
            case false => responseWithoutCompression
        } else responseWithoutCompression
    }
  }

  private def isValidRange(start: Long, end: Option[Long], size: Long): Boolean = start < size &&
    (end match {
      case Some(end) => start >= 0 && start <= end
      case None      => start >= 0 || size + start - 1 >= 0
    })

  // Attempt to find a Range header and collect only the subrange of content requested
  def responseWithRangeSupport[F[_]](request: Request[F], size: Long, additionalHeaders: Headers, rangeResponseFn: (Long, Long) => Stream[F, Byte])(
    using F: Async[F]
  ): F[Response[F]] = {

    val rangeNotSatisfiableResponse: F[Response[F]] = F.pure {
      Response[F](
        status  = Status.RangeNotSatisfiable,
        headers = Headers.apply(`Accept-Ranges`(RangeUnit.Bytes), `Content-Range`(SubRange(0, size - 1), Some(size)))
      )
    }

    def createResponse(start: Long, end: Long, partial: Boolean) = {
      F.pure(rangeResponseFn(start, end + 1)).map {
        byteStream =>
          val rangeHeaders = Headers(`Accept-Ranges`(RangeUnit.Bytes), `Content-Range`(SubRange(start, end), Some(size)), `Content-Length`(size))
          Response(status = if partial then Status.PartialContent else Status.Ok, headers = additionalHeaders ++ rangeHeaders, body = byteStream)
      }
    }

    request.headers.get[Range] match {
      case Some(Range(RangeUnit.Bytes, NonEmptyList(SubRange(s, e), Nil))) =>
        if isValidRange(s, e, size) then {
          val start = if s >= 0 then s else math.max(0, size + s)
          val end   = math.min(size - 1, e.getOrElse(size - 1)) // end is inclusive
          createResponse(start, end, true)
        } else { rangeNotSatisfiableResponse }
      case _                                                               => request.headers.get(ci"Range") match {
          case Some(_) => rangeNotSatisfiableResponse
          case None    => createResponse(0, size - 1, false)
        }
    }
  }
}
