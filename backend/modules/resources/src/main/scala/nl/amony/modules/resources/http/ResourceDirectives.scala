package nl.amony.modules.resources.http

import cats.data.NonEmptyList
import cats.effect.{Async, IO}
import cats.implicits.{toFlatMapOps, toFunctorOps}
import fs2.Stream
import fs2.compression.Compression
import fs2.io.file.{Files, Path}
import org.http4s.*
import org.http4s.headers.Range.SubRange
import org.http4s.headers.{
  ETag,
  Range,
  `Accept-Encoding`,
  `Accept-Ranges`,
  `Content-Encoding`,
  `Content-Length`,
  `Content-Range`,
  `Content-Type`,
  `If-None-Match`
}
import org.typelevel.ci.CIStringSyntax
import scribe.Logging

import nl.amony.modules.resources.api.{ResourceContent, ResourceContentWithRangeSupport}

object ResourceDirectives extends Logging {

  private val rangedResponseCutoff: Long = 1024 * 1024 // 1 MB

  def resourceContentsResponse(req: Request[IO], resource: ResourceContent): IO[Response[IO]] = {

    val maybeMediaType = MediaType.parse(resource.contentType).toOption
    val supportsRange  = resource match
      case content: ResourceContentWithRangeSupport => content.size > rangedResponseCutoff
      case _                                        => false

    // Text responses are cheap to compress and often highly repetitive. Ranged content is left untouched,
    // since compressing ranges would invalidate the byte offsets.
    val canCompress     = !supportsRange && maybeMediaType.exists(_.mainType == "text")
    val useGzip         = canCompress && acceptsGzip(req)
    val eTagValue       = resource.eTag.map(tag => if useGzip then s"$tag-gzip" else tag)
    val eTagHeaders     = eTagValue.map(tag => `ETag`(EntityTag(tag))).toList
    val varyHeaders     = if canCompress then Headers(Header.Raw(ci"Vary", "Accept-Encoding")) else Headers.empty
    val encodingHeaders = if useGzip then Headers(`Content-Encoding`(ContentCoding.gzip)) else Headers.empty

    // If-None-Match takes precedence over Range, so a matching validator short-circuits to 304 before any body is read.
    if isNotModified(req, eTagValue) then
      IO.pure(Response(status = Status.NotModified, headers = Headers(eTagHeaders) ++ varyHeaders ++ encodingHeaders))
    else {
      val additionalHeaders =
        Headers(maybeMediaType.map(mediaType => `Content-Type`(mediaType)).toList) ++ Headers(eTagHeaders) ++ varyHeaders ++ encodingHeaders

      resource match {
        case content: ResourceContentWithRangeSupport if supportsRange =>
          ResourceDirectives.responseWithRangeSupport[IO](
            request           = req,
            size              = content.size,
            additionalHeaders = additionalHeaders,
            rangeResponseFn   = content.streamRange
          )
        case _                                                         =>
          val body = if useGzip then resource.stream.through(Compression.forIO.gzip()) else resource.stream
          IO.pure(Response(status = Status.Ok, headers = additionalHeaders, body = body))
      }
    }
  }

  private def acceptsGzip(req: Request[IO]): Boolean =
    req.headers.get[`Accept-Encoding`].exists(_.satisfiedBy(ContentCoding.gzip))

  /** Whether the request carries an `If-None-Match` that matches the resource's validator (weak comparison, per RFC 7232). */
  private def isNotModified(req: Request[IO], eTag: Option[String]): Boolean =
    eTag.exists(tag =>
      req.headers.get[`If-None-Match`].exists {
        case `If-None-Match`(None)       => true // "*" matches any existing representation
        case `If-None-Match`(Some(tags)) => tags.exists(_.tag == tag)
      }
    )

  def responseFromFile[F[_]](
    req: Request[F],
    path: Path,
    chunkSize: Int,
    additionalHeaders: Headers = Headers.empty,
    detectMediaType: Boolean   = true,
    useCompression: Boolean    = true
  )(using F: Async[F]): F[Response[F]] = {
    Files[F].getBasicFileAttributes(path).flatMap { fileAttributes =>
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
          case true  =>
            responseFromFile(
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
      F.pure(rangeResponseFn(start, end + 1)).map { byteStream =>
        val rangeHeaders =
          Headers(`Accept-Ranges`(RangeUnit.Bytes), `Content-Range`(SubRange(start, end), Some(size)), `Content-Length`(end - start + 1))
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
