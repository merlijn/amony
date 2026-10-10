package nl.amony.lib.messagebus

import cats.effect.IO
import fs2.Stream
import skunk.Session

case class EventTopicKey[E: PersistenceCodec](name: String):
  val persistenceCodec: PersistenceCodec[E] = summon[PersistenceCodec[E]]

trait EventTopic[E]:

  /** Publish an event in its own transaction. */
  def publish(event: E): IO[Unit]

  /**
   * Publish an event using the caller's session, so that persisting the event is atomic with the caller's own writes
   * (and the notification is only sent once that transaction commits).
   */
  def publish(session: Session[IO], event: E): IO[Unit]

  /**
   * An at-least-once consumer that processes a topic's events strictly in insertion order, one at a time, so that
   * events for the same resource are never applied out of order. A processor failure is retried in place, blocking the
   * topic until it succeeds, so no event is skipped; only an undecodable payload is parked as failed.
   */
  def processAtLeastOnce(processorId: String)(processor: E => IO[Unit]): Stream[IO, Unit]
