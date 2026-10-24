package nl.amony.platform.messagebus

import cats.effect.IO
import fs2.Stream
import io.circe.Codec
import skunk.Session

case class MessageTopicKey[E: Codec](name: String):
  val codec: Codec[E] = summon[Codec[E]]

trait MessageTopic[E]:

  /** Publish a message in its own transaction. */
  def publish(message: E): IO[Unit]

  /**
   * Publish a message using the caller's session, so that persisting the message is atomic with the caller's own writes
   * (and the notification is only sent once that transaction commits).
   */
  def publish(session: Session[IO], message: E): IO[Unit]

  /**
   * An at-least-once consumer group that processes a topic's messages strictly in insertion order, one at a time,
   * from its own committed offset, so each group reads the whole log independently and messages for the same
   * resource are never applied out of order. Any failure is retried in place, blocking the group until it succeeds,
   * so no message is ever skipped.
   */
  def processAtLeastOnce(consumerId: String)(processor: E => IO[Unit]): Stream[IO, Unit]
