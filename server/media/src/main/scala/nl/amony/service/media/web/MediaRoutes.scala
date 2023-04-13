package nl.amony.service.media.web

import cats.effect.IO
import nl.amony.lib.cats.FutureOps
import nl.amony.service.media.MediaServiceImpl
import nl.amony.service.media.web.MediaWebModel.MediaMeta
import JsonCodecs._
import io.grpc.{Status, StatusRuntimeException}
import nl.amony.service.media.api.{DeleteByIdResponse, GetById, UpdateMediaMeta}
import org.http4s.{EntityEncoder, HttpRoutes, Response}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe._
import org.http4s.dsl.io._
import nl.amony.service.media.{api => grcpApi}

import scala.concurrent.Future

object MediaRoutes {

  private def recoverFromGrpcError[T]: PartialFunction[Throwable, IO[Response[IO]]] = {
      case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.NOT_FOUND        => NotFound()
      case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.INVALID_ARGUMENT => BadRequest()
      case _ => InternalServerError()
    }

  def wrapServiceRequest[T](f: => Future[T])(implicit e: EntityEncoder[IO, T]): IO[Response[IO]] =
    IO.fromFuture(IO(f)).flatMap(Ok(_)).recoverWith(recoverFromGrpcError)

  def wrapServiceRequest[T](f: => Future[T], r: T => IO[Response[IO]]): IO[Response[IO]] =
    IO.fromFuture(IO(f)).flatMap(r).recoverWith(recoverFromGrpcError)

  def apply(mediaService: MediaServiceImpl): HttpRoutes[IO] = {

    HttpRoutes.of[IO] {
      case GET        -> Root / "api" / "media" / mediaId =>

        val req = GetById(mediaId)

        wrapServiceRequest(mediaService.getById(req))

      case req @ POST -> Root / "api" / "media" / mediaId =>

        for {
          meta          <- req.decodeJson[MediaMeta]
          serviceRequest = UpdateMediaMeta(mediaId, grcpApi.MediaMeta(meta.title, meta.comment, meta.tags))
          response      <- wrapServiceRequest(mediaService.updateMediaMeta(serviceRequest))
        } yield response

      case DELETE -> Root / "api" / "media" / mediaId =>

        val req = grcpApi.DeleteById(mediaId)

        wrapServiceRequest(mediaService.deleteById(req), (_: DeleteByIdResponse) => NoContent())
    }
  }
}
