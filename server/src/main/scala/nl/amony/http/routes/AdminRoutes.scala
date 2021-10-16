package nl.amony.http.routes

import akka.http.scaladsl.server.Directives._
import nl.amony.http.RouteDeps

trait AdminRoutes {

  self: RouteDeps =>

  val adminRoutes = pathPrefix("api" / "admin") {
    (path("regen-previews") & post) {
      api.admin.regeneratePreviews()
      complete("OK")
    } ~ (path("export-to-file") & post) {
      api.admin.exportLibrary()
      complete("OK")
    } ~ (path("verify-hashes") & post) {
      api.admin.verifyHashes()
      complete("OK")
    } ~ (path("convert-non-streamable-videos") & post) {
      api.admin.convertNonStreamableVideos()
      complete("OK")
    } ~ (path("scan-library") & post) {
      api.admin.scanLibrary()
      complete("OK")
    } ~ (path("logs")) {
      complete("")
    }
  }
}
