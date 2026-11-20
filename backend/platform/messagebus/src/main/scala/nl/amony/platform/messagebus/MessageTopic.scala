package nl.amony.platform.messagebus

import scala.util.hashing.MurmurHash3

import cats.effect.IO
import fs2.Stream
import io.circe.Codec
import skunk.Session

case class MessageTopicKey[E: Codec](
  name: String,
  messageKey: E => String,
  partitions: Int = MessageTopicKey.DefaultPartitions
):
  require(partitions >= 1, s"partitions must be >= 1, got $partitions")

  val codec: Codec[E] = summon[Codec[E]]

  def partitionFor(message: E): (String, Int) =
    val key       = messageKey(message)
    val partition = Math.floorMod(MurmurHash3.stringHash(key), partitions)
    (key, partition)

object MessageTopicKey:
  val DefaultPartitions: Int = 16

trait MessageTopic[E]:

  /** Publish a message in its own transaction. */
  def publish(message: E): IO[Unit]

  /**
   * Publish a message using the caller's session, so that persisting the message is atomic with the caller's own writes
   * (and the notification is only sent once that transaction commits).
   */
  def publish(session: Session[IO], message: E): IO[Unit]

  /**
   * An at-least-once consumer group that acknowledges each partition's messages in log order, retrying failures in
   * place before advancing. New groups start at the latest committed offsets. Up to `parallelism` partitions
   * (defaulting to the bus configuration) are drained concurrently on this node.
   *
   * A worker that loses its lease cannot advance the offset, but cancellation cannot undo side effects already in
   * flight. Processors must tolerate duplicates and protect side effects that cannot be applied out of order.
   */
  def processAtLeastOnce(consumerId: String, parallelism: Option[Int] = None)(processor: E => IO[Unit]): Stream[IO, Unit]
