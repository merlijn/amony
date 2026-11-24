package nl.amony.platform.messagebus

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.util.hashing.MurmurHash3

import cats.effect.{IO, Resource}
import fs2.Stream
import io.circe.parser.parse
import io.circe.{Codec, Error}
import pureconfig.ConfigReader
import skunk.Session

import nl.amony.platform.messagebus.impl.{JobBusImpl, JobStore, MessageLog, MessageTopicImpl}

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

  def encode(message: E): Array[Byte] = codec(message).noSpaces.getBytes(UTF_8)

  def decode(payload: Array[Byte]): Either[Error, E] =
    parse(new String(payload, UTF_8)).flatMap(codec.decodeJson)

object MessageTopicKey:
  val DefaultPartitions: Int = 16

/** Durable, partitioned topic supporting atomic publishing and at-least-once consumer groups. */
trait MessageTopic[E]:

  /** Publish a message in its own transaction. */
  def publish(message: E): IO[Unit]

  /** Publish a message in the caller's transaction; notifications are delivered on commit. */
  def publish(session: Session[IO], message: E): IO[Unit]

  /** Publish a batch of messages in one transaction, locking partitions in canonical order to avoid deadlocks. */
  def publishAll(messages: Seq[E]): IO[Unit] =
    messages.foldLeft(IO.unit)((acc, message) => acc >> publish(message))

  /** Publish a batch in the caller's transaction, locking partitions in canonical order with a single notification. */
  def publishAll(session: Session[IO], messages: Seq[E]): IO[Unit] =
    messages.foldLeft(IO.unit)((acc, message) => acc >> publish(session, message))

  /**
   * Consume messages at least once in per-partition order, retrying failures in place. New groups start at the latest
   * offsets and drain up to `parallelism` partitions concurrently. Processors must tolerate duplicates and protect
   * external side effects if a lease is lost in flight.
   */
  def processAtLeastOnce(consumerId: String, parallelism: Option[Int] = None)(processor: E => IO[Unit]): Stream[IO, Unit]

/** Entry point for obtaining cached [[MessageTopic]] and [[JobBus]] handles. */
trait MessageBus:

  /** Return the [[MessageTopic]] for the given implicit key. */
  def getTopic[E](using key: MessageTopicKey[E]): MessageTopic[E] = getTopicForKey[E](key)

  /** Return the [[MessageTopic]] for `key`. */
  def getTopicForKey[E](key: MessageTopicKey[E]): MessageTopic[E]

  /** Return a [[JobBus]] with a status sidecar (opt-in: writes one extra status row per job). */
  def getJobBus[P](using key: JobTopicKey[P]): JobBus[P]

object MessageBus:

  def postgres(pool: Resource[IO, Session[IO]], config: MessageBusConfig = MessageBusConfig()): MessageBus =
    new MessageBus:

      private val log    = MessageLog(pool)
      private val topics = ConcurrentHashMap[String, MessageTopic[?]]()
      private val jobs   = ConcurrentHashMap[String, JobBus[?]]()

      override def getTopicForKey[E](key: MessageTopicKey[E]): MessageTopic[E] =
        topics.computeIfAbsent(key.name, _ => MessageTopicImpl(key, log, pool, config)).asInstanceOf[MessageTopic[E]]

      override def getJobBus[P](using key: JobTopicKey[P]): JobBus[P] =
        jobs
          .computeIfAbsent(
            key.name,
            _ => JobBusImpl(key, getTopicForKey(key.topicKey), log, JobStore(pool), pool, config)
          )
          .asInstanceOf[JobBus[P]]

final case class MessageBusConfig(
  // Fallback poll for notifications that were lost or whose consumer crashed. The NOTIFY path handles the
  // common case; this bounds how long a missed notification, or a message awaiting a retry, can sit.
  pollInterval: FiniteDuration  = 3.seconds,
  // Wait before retrying a failed message, so a failing processor (e.g. Solr down) is not hammered.
  retryBackoff: FiniteDuration  = 3.seconds,
  // How long a consumer holds a partition lease before another node can take over if the consumer crashes.
  // Renewed periodically while a message is actively being processed.
  leaseDuration: FiniteDuration = 30.seconds,
  // Default number of partitions a single consumer group instance can drain concurrently on one node.
  parallelism: Int              = 4,
  purgeInterval: FiniteDuration = 1.hour,
  retention: FiniteDuration     = 7.days,
  notificationQueueSize: Int    = 10000
) derives ConfigReader:
  require(parallelism >= 1, s"parallelism must be >= 1, got $parallelism")
  def retryBackoffDuration: java.time.Duration = java.time.Duration.ofMillis(retryBackoff.toMillis)
  def leaseJavaDuration: java.time.Duration    = java.time.Duration.ofMillis(leaseDuration.toMillis)
  def retentionDuration: java.time.Duration    = java.time.Duration.ofMillis(retention.toMillis)
