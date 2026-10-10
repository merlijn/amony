package nl.amony.lib.messagebus

import java.util.concurrent.ConcurrentHashMap

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
   * An at-least-once consumer. Claims pending events (and events left claimed by a crashed consumer), runs the
   * processor for each, then marks the event as processed. A failing event is retried up to a configured maximum,
   * after which it is parked as failed without affecting any other event.
   */
  def processAtLeastOnce(processorId: String, batchSize: Int)(processor: E => IO[Unit]): Stream[IO, Int]

object EventTopic:

  final class TransientEventTopic[E] extends EventTopic[E]:
    val processors = new ConcurrentHashMap[String, E => Unit]

    override def publish(event: E): IO[Unit] = IO(processors.values().forEach(processor => processor(event)))

    override def publish(session: Session[IO], event: E): IO[Unit] = publish(event)

    def followTail(listener: E => Unit): Unit = processors.putIfAbsent("", listener)

    override def processAtLeastOnce(processorId: String, batchSize: Int)(processor: E => IO[Unit]): Stream[IO, Int] = ???

  def transientEventTopic[E](): TransientEventTopic[E] = new TransientEventTopic[E]
