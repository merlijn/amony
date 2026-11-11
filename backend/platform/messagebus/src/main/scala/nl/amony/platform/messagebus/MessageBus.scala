package nl.amony.platform.messagebus

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import pureconfig.ConfigReader
import skunk.Session

import nl.amony.platform.messagebus.impl.{PostgresCommandBus, PostgresMessageTopic}

trait PersistentMessageBus:

  def getTopic[E](using key: MessageTopicKey[E]): MessageTopic[E] = getTopicForKey[E](key)

  def getTopicForKey[E](key: MessageTopicKey[E]): MessageTopic[E]

  /**
   * A durable command queue with a producer-visible status sidecar. Unlike [[getTopic]] it writes one extra row per
   * submitted command, so it is opt-in: only construct it for command topics.
   */
  def getCommandBus[P](using key: CommandTopicKey[P]): CommandBus[P]

object PersistentMessageBus:

  def postgres(pool: Resource[IO, Session[IO]], config: MessageBusConfig = MessageBusConfig()): PersistentMessageBus =
    new PersistentMessageBus:

      private val log      = MessageLog(pool)
      private val topics   = ConcurrentHashMap[String, MessageTopic[?]]()
      private val commands = ConcurrentHashMap[String, CommandBus[?]]()

      override def getTopicForKey[E](key: MessageTopicKey[E]): MessageTopic[E] =
        topics.computeIfAbsent(key.name, _ => PostgresMessageTopic(key, log, pool, config)).asInstanceOf[MessageTopic[E]]

      override def getCommandBus[P](using key: CommandTopicKey[P]): CommandBus[P] =
        commands
          .computeIfAbsent(
            key.name,
            _ => PostgresCommandBus(key, getTopicForKey(key.topicKey), log, CommandStore(pool), pool, config)
          )
          .asInstanceOf[CommandBus[P]]

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
