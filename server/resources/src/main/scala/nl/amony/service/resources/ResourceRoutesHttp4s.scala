package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.cats.FutureOps
import nl.amony.service.resources.ResourceRoutes.patterns
import org.http4s._
import org.http4s.dsl.io._
import scribe.Logging

object ResourceRoutesHttp4s extends Logging {

  import ResourceRoutes.patterns._

  def videoResponse(req: Request[IO], ioResponse: IOResponse) =
    ResourceDirectivesHttp4s.rangedResponse[IO](
      req,
      ioResponse.size(),
      Some(MediaType.video.mp4),
      ioResponse.getContentRangeFs2
    )

  def apply(buckets: Map[String, ResourceBucket]) = {
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "resources" / "media" / bucketId / resourceId => resourceId match {

        case patterns.Thumbnail(id, _, timestamp, quality) =>
          buckets(bucketId).getThumbnail(id, quality.toInt, timestamp.toLong).toIO.flatMap {
            case None             => NotFound()
            case Some(ioResponse) => Ok(ioResponse.getContentFs2())
          }

        case patterns.Video(id, quality) =>
          buckets(bucketId).getResource(id, quality.toInt).toIO.flatMap {
            case None             => NotFound()
            case Some(ioResponse) => videoResponse(req, ioResponse)
          }

        case patterns.VideoFragment(id, start, end, quality) =>
          buckets(bucketId).getVideoFragment(id, start.toInt, end.toInt, quality.toInt).toIO.flatMap {
            case None             => NotFound()
            case Some(ioResponse) => videoResponse(req, ioResponse)
          }
      }
    }
  }
}
