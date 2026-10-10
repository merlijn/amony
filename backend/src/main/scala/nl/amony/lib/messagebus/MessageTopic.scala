package nl.amony.lib.messagebus

import cats.effect.IO
import fs2.Stream
import skunk.Session

case class MessageTopicKey[E: PersistenceCodec](name: String):
  val persistenceCodec: PersistenceCodec[E] = summon[PersistenceCodec[E]]

trait MessageTopic[E]:

  /** Publish a message in its own transaction. */
  def publish(message: E): IO[Unit]

  /**
   * Publish a message using the caller's session, so that persisting the message is atomic with the caller's own writes
   * (and the notification is only sent once that transaction commits).
   */
  def publish(session: Session[IO], message: E): IO[Unit]

  /**
   * An at-least-once consumer that processes a topic's messages strictly in insertion order, one at a time, so that
   * messages for the same resource are never applied out of order. A processor failure is retried in place, blocking the
   * topic until it succeeds, so no message is skipped; only an undecodable payload is parked as failed.
   */
  def processAtLeastOnce(processorId: String)(processor: E => IO[Unit]): Stream[IO, Unit]
