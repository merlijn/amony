package nl.amony.platform.messagebus

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import pureconfig.ConfigReader
import skunk.Session

import nl.amony.platform.messagebus.impl.{JobStore, MessageLog, PostgresJobBus, PostgresMessageTopic}

trait MessageBus:

  def getTopic[E](using key: MessageTopicKey[E]): MessageTopic[E] = getTopicForKey[E](key)

  def getTopicForKey[E](key: MessageTopicKey[E]): MessageTopic[E]

  /**
   * A durable job queue with a producer-visible status sidecar. Unlike [[getTopic]] it writes one extra row per
   * submitted job, so it is opt-in: only construct it for job topics.
   */
  def getJobBus[P](using key: JobTopicKey[P]): JobBus[P]

object MessageBus:

  def postgres(pool: Resource[IO, Session[IO]], config: MessageBusConfig = MessageBusConfig()): MessageBus =
    new MessageBus:

      private val log    = MessageLog(pool)
      private val topics = ConcurrentHashMap[String, MessageTopic[?]]()
      private val jobs   = ConcurrentHashMap[String, JobBus[?]]()

      override def getTopicForKey[E](key: MessageTopicKey[E]): MessageTopic[E] =
        topics.computeIfAbsent(key.name, _ => PostgresMessageTopic(key, log, pool, config)).asInstanceOf[MessageTopic[E]]

      override def getJobBus[P](using key: JobTopicKey[P]): JobBus[P] =
        jobs
          .computeIfAbsent(
            key.name,
            _ => PostgresJobBus(key, getTopicForKey(key.topicKey), log, JobStore(pool), pool, config)
          )
          .asInstanceOf[JobBus[P]]

final case class MessageBusConfig(
  // Fallback poll for notifications that were lost or whose consumer crashed. The NOTIFY path handles the
  // common case; this bounds how long a missed notification, or a message awaiting a retry, can sit.
  pollInterval: FiniteDuration          = 3.seconds,
  // Wait before retrying a failed message, so a failing processor (e.g. Solr down) is not hammered.
  retryBackoff: FiniteDuration          = 3.seconds,
  // How long a consumer holds a partition lease before another node can take over if the consumer crashes.
  // Renewed periodically while a message is actively being processed.
  leaseDuration: FiniteDuration         = 30.seconds,
  // Default number of partitions a single consumer group instance can drain concurrently on one node.
  parallelism: Int                      = 4,
  purgeInterval: FiniteDuration         = 1.hour,
  retention: FiniteDuration             = 7.days,
  notificationQueueSize: Int            = 10000,
  backlogCheckInterval: FiniteDuration  = 1.minute,
  backlogErrorThreshold: FiniteDuration = 5.minutes
) derives ConfigReader:
  require(parallelism >= 1, s"parallelism must be >= 1, got $parallelism")
  require(backlogCheckInterval > Duration.Zero, "backlog check interval must be positive")
  require(backlogErrorThreshold > Duration.Zero, "backlog error threshold must be positive")
  def retryBackoffDuration: java.time.Duration = java.time.Duration.ofMillis(retryBackoff.toMillis)
  def leaseJavaDuration: java.time.Duration    = java.time.Duration.ofMillis(leaseDuration.toMillis)
  def retentionDuration: java.time.Duration    = java.time.Duration.ofMillis(retention.toMillis)
