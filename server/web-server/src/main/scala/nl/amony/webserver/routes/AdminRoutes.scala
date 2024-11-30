package nl.amony.webserver.routes

import cats.effect.IO
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import org.http4s.*
import org.http4s.dsl.io.*

object AdminRoutes:
  
  def apply(searchService: SearchService): Unit = {
    HttpRoutes.of[IO] {

      case req @ GET -> Root / "api" / "admin" / "re-index" => 
        val bucketId = req.params.get("bucketId")
        
//        searchService.reIndex()
        Ok()
    }
  }
