package nl.amony.lib.eventstore

import cats.effect.IO
import fs2.Stream

trait EventSourcedEntity[S, E] {

  def eventCount(): IO[Long]

  def events(start: Long = 0L): Stream[IO, E]

  def followEvents(start: Long = 0L): Stream[IO, E]

  def follow(start: Long = 0L): Stream[IO, (E, S)]

  def persist(e: E): IO[S]

  def update(fn: S => IO[E]): IO[S]

  def state(): IO[S]
}
