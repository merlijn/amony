package nl.amony.lib.tapir

import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.{header, statusCode}

case class RedirectResponse(location: String)

object RedirectResponse:
  val endpointOutput = (statusCode(StatusCode.Found) and header[String](HeaderNames.Location)).mapTo[RedirectResponse]
