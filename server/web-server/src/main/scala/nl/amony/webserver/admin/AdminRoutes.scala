package nl.amony.webserver.admin

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import nl.amony.webserver.WebServerConfig
import nl.amony.webserver.admin.AdminApi

object AdminRoutes {

  def apply(adminApi: AdminApi, config: WebServerConfig): Route = {

    implicit val timeout: Timeout = Timeout(config.requestTimeout)

    pathPrefix("api" / "admin") {
      (path("regen-preview-thumbnails") & post) {
        adminApi.reGeneratePreviewSprites()
        complete(StatusCodes.OK)
      } ~ (path("export-to-file") & post) {
        val json = adminApi.exportLibrary()
        complete(StatusCodes.OK, json)
      } ~ (path("verify-hashes") & post) {
        adminApi.verifyHashes()
        complete(StatusCodes.OK)
      } ~ (path("update-hashes") & post) {
        adminApi.updateHashes()
        complete(StatusCodes.OK)
      } ~ (path("convert-non-streamable-videos") & post) {
        adminApi.convertNonStreamableVideos()
        complete(StatusCodes.OK)
      } ~ (path("scan-library") & post) {
//        adminApi.scanLibrary()
        complete(StatusCodes.OK)
      } ~ (path("logs")) {
        complete(StatusCodes.OK)
      } ~ {
        complete(StatusCodes.NotFound)
      }
    }
  }
}
