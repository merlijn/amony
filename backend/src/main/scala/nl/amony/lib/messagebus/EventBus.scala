package nl.amony.lib.messagebus

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import skunk.Session

trait PersistentEventBus:

  def getTopic[E](using key: EventTopicKey[E]): EventTopic[E] = getTopicForKey[E](key)

  def getTopicForKey[E](key: EventTopicKey[E]): EventTopic[E]

object PersistentEventBus:

  def postgres(pool: Resource[IO, Session[IO]], config: EventBusConfig = EventBusConfig()): PersistentEventBus =
    new PersistentEventBus:

      private val outbox = EventOutbox(pool)
      private val topics = ConcurrentHashMap[String, EventTopic[?]]()

      override def getTopicForKey[E](key: EventTopicKey[E]): EventTopic[E] =
        topics.computeIfAbsent(key.name, _ => PostgresEventTopic(key, outbox, pool, config)).asInstanceOf[EventTopic[E]]

final case class EventBusConfig(
  // Fallback poll for notifications that were lost or whose consumer crashed. Aligned with the Solr
  // `commit-within-millis` so a missed notification is picked up within the window Solr would have committed in anyway.
  pollInterval: FiniteDuration  = 500.millis,
  purgeInterval: FiniteDuration = 1.hour,
  retention: FiniteDuration     = 7.days,
  notificationQueueSize: Int    = 10000
):
  def retentionDuration: java.time.Duration = java.time.Duration.ofMillis(retention.toMillis)
