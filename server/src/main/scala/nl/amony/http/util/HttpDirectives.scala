package nl.amony.http.util

import akka.NotUsed
import akka.http.scaladsl.model.Multipart.ByteRanges
import akka.http.scaladsl.model.StatusCodes.{PartialContent, RangeNotSatisfiable, TooManyRequests}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ByteRange, Range, RangeUnits, `Content-Range`, `Content-Type`}
import akka.http.scaladsl.server.Directives.{as, entity, post, withSizeLimit}
import akka.http.scaladsl.server.directives.FutureDirectives.onSuccess
import akka.http.scaladsl.server.directives.{ContentTypeResolver, FileInfo}
import akka.http.scaladsl.server.{Directive, Directive1, Route, UnsatisfiableRangeRejection}
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import scribe.Logging

import java.nio.file.{Files, Path}
import scala.concurrent.Future

object HttpDirectives extends Logging {
  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.RouteDirectives._

  val chunkSize = 8192

  def postWithData[T](implicit um: FromRequestUnmarshaller[T]): Directive[Tuple1[T]] = post & entity(as[T])

  case class IndexRange(start: Long, end: Long) {
    def length: Long = end - start
    def distance(other: IndexRange): Long = mergedEnd(other) - mergedStart(other) - (length + other.length)
    def mergeWith(other: IndexRange): IndexRange = IndexRange(mergedStart(other), mergedEnd(other))
    def toContentRange(entityLength: Long): ContentRange.Default = ContentRange(start, end - 1, entityLength)
    private def mergedStart(other: IndexRange): Long = math.min(start, other.start)
    private def mergedEnd(other: IndexRange): Long = math.max(end, other.end)
  }

  def fileWithRangeSupport(path: Path)(implicit resolver: ContentTypeResolver): Route = {
      fileWithRangeSupport(path, resolver.apply(path.getFileName.toString))
  }

  def fileWithRangeSupport(path: Path, contentType: ContentType): Route = {
    if(!path.toFile.exists())
      complete(StatusCodes.NotFound)
    else
      randomAccessRangeSupport(
        contentType,
        path.toFile.length(),
        (start, _) => FileIO.fromPath(path, chunkSize, start)
      )
  }

  /** Answers GET requests with an `Accept-Ranges: bytes` header and converts HttpResponses coming back from its inner
    * route into partial responses if the initial request contained a valid `Range` request header. The requested
    * byte-ranges may be coalesced.
    *
    * Rejects requests with unsatisfiable ranges `UnsatisfiableRangeRejection`.
    * Rejects requests with too many expected ranges.
    *
    * @see [[https://tools.ietf.org/html/rfc7233]]
    */
  def randomAccessRangeSupport(
      contentType: ContentType,
      contentLength: Long,
      byteStringProvider: (Long, Long) => Source[ByteString, Any]
  ): Route = {

    extractRequestContext { ctx =>
      val settings = ctx.settings
      import settings.{rangeCoalescingThreshold, rangeCountLimit}

      def toIndexRange(range: ByteRange): IndexRange =
        range match {
          case ByteRange.Slice(start, end)    => IndexRange(start, math.min(end + 1, contentLength))
          case ByteRange.FromOffset(first)    => IndexRange(first, contentLength)
          case ByteRange.Suffix(suffixLength) => IndexRange(math.max(0, contentLength - suffixLength), contentLength)
        }

      // See comment of the `range-coalescing-threshold` setting in `reference.conf` for the rationale of this behavior.
      def coalesceRanges(iRanges: Seq[IndexRange]): Seq[IndexRange] =
        iRanges.foldLeft(Seq.empty[IndexRange]) { (acc, iRange) =>
          val (mergeCandidates, otherCandidates) = acc.partition(_.distance(iRange) <= rangeCoalescingThreshold)
          val merged                             = mergeCandidates.foldLeft(iRange)(_ mergeWith _)
          otherCandidates :+ merged
        }

      // at this point ranges is guaranteed to be of size > 1
      def multipartRanges(ranges: Seq[ByteRange]): Multipart.ByteRanges = {

        val iRanges: Seq[IndexRange] = ranges.map(toIndexRange)
        val coalescedRanges          = coalesceRanges(iRanges).sortBy(_.start)

        val source: Source[ByteRanges.BodyPart, NotUsed] = coalescedRanges.size match {

          case 0 => Source.empty
          case 1 =>
            val range      = coalescedRanges.head
            val byteSource = byteStringProvider(range.start, range.length)
            val part = Multipart.ByteRanges.BodyPart(
              range.toContentRange(contentLength),
              HttpEntity(contentType, range.length, byteSource)
            )
            Source.single(part)
          case _ =>
            Source.fromIterator(() => coalescedRanges.iterator).map { range =>
              val byteSource = byteStringProvider(range.start, range.length)
              Multipart.ByteRanges.BodyPart(
                range.toContentRange(contentLength),
                HttpEntity(contentType, range.length, byteSource))
            }
        }

        Multipart.ByteRanges(source)
      }

      def isRangeSatisfiable(range: ByteRange): Boolean =
        range match {
          case ByteRange.Slice(firstPos, _)   => firstPos < contentLength
          case ByteRange.FromOffset(firstPos) => firstPos < contentLength
          case ByteRange.Suffix(length)       => length > 0
        }

      def singleRangeResponse(range: IndexRange) = {
        val byteSource = byteStringProvider(range.start, range.length)
        val entity     = HttpEntity(contentType, byteSource)
        complete(HttpResponse(PartialContent, Seq(`Content-Range`(range.toContentRange(contentLength))), entity))
      }

      ctx.request.header[Range] match {

        case Some(Range(RangeUnits.Bytes, ranges)) =>
          if (ranges.size <= rangeCountLimit)
            ranges.filter(isRangeSatisfiable) match {
              case Nil =>
                logger.debug("reject: no satisfiable ranges")
                reject(UnsatisfiableRangeRejection(ranges, contentLength))
              case Seq(singleRange) =>
                singleRangeResponse(toIndexRange(singleRange))
              case multipleRanges =>
                complete(PartialContent, Seq(`Content-Type`(contentType)), multipartRanges(multipleRanges))
            }
          else {
            complete(TooManyRequests)
          }

        case Some(_) =>
          complete(RangeNotSatisfiable)

        case None =>
          singleRangeResponse(IndexRange(0, contentLength))
      }
    }
  }

  def uploadFiles[T](fieldName: String, uploadLimitBytes: Long)(uploadFn: (FileInfo, Source[ByteString, Any]) => Future[T]): Directive1[Seq[T]] =

    (withSizeLimit(uploadLimitBytes) & entity(as[Multipart.FormData])).flatMap { formData =>

      extractRequestContext.flatMap { ctx =>
        implicit val mat = ctx.materializer
        implicit val ec = ctx.executionContext

        val uploaded: Source[T, Any] = formData.parts
          .mapConcat { part =>
            if (part.filename.isDefined && part.name == fieldName) part :: Nil
            else {
              part.entity.discardBytes()
              Nil
            }
          }
          .mapAsync(1) { part =>
            val fileInfo = FileInfo(part.name, part.filename.get, part.entity.contentType)
            uploadFn(fileInfo, part.entity.dataBytes)
          }

        val uploadedF: Future[Seq[T]] = uploaded.runWith(Sink.seq[T])

        onSuccess(uploadedF)
      }
    }
}
