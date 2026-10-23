package nl.amony.lib.messagebus

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import pureconfig.ConfigReader
import skunk.Session

trait PersistentMessageBus:

  def getTopic[E](using key: MessageTopicKey[E]): MessageTopic[E] = getTopicForKey[E](key)

  def getTopicForKey[E](key: MessageTopicKey[E]): MessageTopic[E]

object PersistentMessageBus:

  def postgres(pool: Resource[IO, Session[IO]], config: MessageBusConfig = MessageBusConfig()): PersistentMessageBus =
    new PersistentMessageBus:

      private val log    = MessageLog(pool)
      private val topics = ConcurrentHashMap[String, MessageTopic[?]]()

      override def getTopicForKey[E](key: MessageTopicKey[E]): MessageTopic[E] =
        topics.computeIfAbsent(key.name, _ => PostgresMessageTopic(key, log, pool, config)).asInstanceOf[MessageTopic[E]]

final case class MessageBusConfig(
  // Fallback poll for notifications that were lost or whose consumer crashed. The NOTIFY path handles the
  // common case; this bounds how long a missed notification, or a message awaiting a retry, can sit.
  pollInterval: FiniteDuration  = 3.seconds,
  // Wait before retrying a failed message, so a failing processor (e.g. Solr down) is not hammered.
  retryBackoff: FiniteDuration  = 3.seconds,
  purgeInterval: FiniteDuration = 1.hour,
  retention: FiniteDuration     = 7.days,
  notificationQueueSize: Int    = 10000
) derives ConfigReader:
  def retentionDuration: java.time.Duration = java.time.Duration.ofMillis(retention.toMillis)
