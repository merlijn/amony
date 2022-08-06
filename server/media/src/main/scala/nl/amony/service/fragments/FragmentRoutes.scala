package nl.amony.service.fragments

import akka.http.scaladsl.server.Directives._
import nl.amony.service.media.JsonCodecs
import nl.amony.service.media.MediaWebModel.{MediaMeta, Range}
import akka.http.scaladsl.model._
import io.circe.syntax._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import nl.amony.service.fragments.WebModel.Fragment
import nl.amony.service.resources.ResourceConfig.TranscodeSettings

class FragmentRoutes(fragmentService: FragmentService, transcodeSettings: List[TranscodeSettings]) {

  val jsonCodecs = new JsonCodecs(transcodeSettings)
  import jsonCodecs._

  val routes = pathPrefix("api") {
    pathPrefix("fragments" / Segment) { mediaId =>
      path("add") {
        (post & entity(as[Range])) { createFragment =>
          onSuccess(fragmentService.addFragment(mediaId, createFragment.from, createFragment.to)) {
            fragment => complete(Fragment.fromProtocol(transcodeSettings, fragment))
          }
        }
      } ~ path(Segment) { idx =>
        delete {
          complete(fragmentService.deleteFragment(mediaId, idx.toInt))
        } ~ (post & entity(as[Range])) { createFragment =>
          onSuccess(fragmentService.updateFragmentRange(mediaId, idx.toInt, createFragment.from, createFragment.to)) {
            fragment => complete(Fragment.fromProtocol(transcodeSettings, fragment))
          }
        }
      } ~ path(Segment / "tags") { idx =>
        (post & entity(as[List[String]])) { tags =>
          onSuccess(fragmentService.updateFragmentTags(mediaId, idx.toInt, tags)) {
            fragment => complete(Fragment.fromProtocol(transcodeSettings, fragment))
          }
        }
      }
    }
  }
}
