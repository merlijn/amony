package nl.amony.http.util

import akka.NotUsed
import akka.http.scaladsl.model.Multipart.ByteRanges
import akka.http.scaladsl.model.StatusCodes.PartialContent
import akka.http.scaladsl.model.StatusCodes.RangeNotSatisfiable
import akka.http.scaladsl.model.StatusCodes.TooManyRequests
import akka.http.scaladsl.model.headers.ByteRange
import akka.http.scaladsl.model.headers.Range
import akka.http.scaladsl.model.headers.RangeUnits
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.headers.`Content-Range`
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.optionalHeaderValueByName
import akka.http.scaladsl.server.Directives.respondWithHeaders
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.UnsatisfiableRangeRejection
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Source
import akka.util.ByteString
import scribe.Logging

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path

object CustomDirectives extends Logging {
  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.RouteDirectives._

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
    randomAccessRangeSupport(
      contentType,
      path.toFile.length(),
      (start, _) => {
        FileIO.fromPath(path, 8192, start)
      }
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
  ) = {

    extractRequestContext { ctx =>
      val settings = ctx.settings
      import settings.rangeCoalescingThreshold
      import settings.rangeCountLimit

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

      // at this point ranges is garuanteed to be of size > 1
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
              Multipart.ByteRanges
                .BodyPart(range.toContentRange(contentLength), HttpEntity(contentType, range.length, byteSource))
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
                logger.warn("reject: no satisfiable ranges")
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

  // TODO remove this once randomAccessFile route has proven to be stable
  def fixedRangeSize(fileName: String) = {
    optionalHeaderValueByName("Range") {
      case None =>
        // there must always be range
        complete(StatusCodes.RangeNotSatisfiable)
      case Some(range) =>
        val file     = new File(fileName)
        val fileSize = file.length()
        val rng      = range.split("=")(1).split("-")
        val start    = rng(0).toLong
        val end = if (rng.length > 1) {
          //there is end range
          rng(1).toLong
        } else {
          fileSize - 1
        }

        respondWithHeaders(
          List(
            RawHeader("Content-Range", s"bytes ${start}-${end}/${fileSize}"),
            RawHeader("Accept-Ranges", s"bytes")
          )
        ) {
          complete {

            val chunkSize = 1024 * 1000 * 4 // read 4MB of data = 4,096,000 Bytes
            val raf       = new RandomAccessFile(file, "r")
            val dataArray = Array.ofDim[Byte](chunkSize)
            raf.seek(start) // start readinng from `start` position
            val bytesRead = raf.read(dataArray, 0, chunkSize)
            val readChunk = dataArray.take(bytesRead)

            HttpResponse(StatusCodes.PartialContent, entity = HttpEntity(MediaTypes.`video/mp4`, readChunk))
          }
        }
    }
  }
}
