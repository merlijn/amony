package nl.amony.http.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import nl.amony.http.RouteDeps

trait AdminRoutes {

  self: RouteDeps =>

  val adminRoutes = pathPrefix("api" / "admin") {
    (path("regen-thumbnails") & post) {
      api.admin.regenerateAllPreviews()
      complete(StatusCodes.OK)
    } ~ (path("regen-preview-thumbnails") & post) {
      api.admin.generateThumbnailPreviews()
      complete(StatusCodes.OK)
    } ~ (path("export-to-file") & post) {
      api.admin.exportLibrary()
      complete(StatusCodes.OK)
    } ~ (path("verify-hashes") & post) {
      api.admin.verifyHashes()
      complete(StatusCodes.OK)
    } ~ (path("update-hashes") & post) {
      api.admin.updateHashes()
      complete(StatusCodes.OK)
    } ~ (path("convert-non-streamable-videos") & post) {
      api.admin.convertNonStreamableVideos()
      complete(StatusCodes.OK)
    } ~ (path("scan-library") & post) {
      api.admin.scanLibrary()
      complete(StatusCodes.OK)
    } ~ (path("logs")) {
      complete(StatusCodes.OK)
    } ~ {
      complete(StatusCodes.NotFound)
    }
  }
}
