package nl.amony.service.resources

import cats.effect.IO

import scala.concurrent.Future
import fs2.Stream

trait ResourceBucket {

  def index(): Stream[IO, String]

  def get(resourceId: String): Future[String]

  def upload(): Unit
}
