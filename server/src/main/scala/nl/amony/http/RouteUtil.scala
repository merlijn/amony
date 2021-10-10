package nl.amony.http

import akka.http.scaladsl.model.{HttpEntity, HttpResponse, MediaTypes, StatusCodes}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives.{complete, optionalHeaderValueByName, respondWithHeaders}

import java.io.{File, RandomAccessFile}

object RouteUtil {

  def streamMovie(fileName: String) = {
    optionalHeaderValueByName("Range") {
      case None =>
        // there must always be range
        complete(StatusCodes.RangeNotSatisfiable)
      case Some(range) =>
        val file = new File(fileName)
        val fileSize = file.length()
        val rng = range.split("=")(1).split("-")
        val start = rng(0).toLong
        val end = if (rng.length > 1) {
          //there is end range
          rng(1).toLong
        } else {
          fileSize - 1
        }

        respondWithHeaders(List(
          RawHeader("Content-Range", s"bytes ${start}-${end}/${fileSize}"),
          RawHeader("Accept-Ranges", s"bytes")
        )) {
          complete {

            val chunkSize = 1024 * 1000 * 4 // read 4MB of data = 4,096,000 Bytes
            val raf = new RandomAccessFile(file, "r")
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
