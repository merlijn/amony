package nl.amony.lib.eventbus

import cats.effect.IO
import fs2.Stream

import java.util.concurrent.ConcurrentHashMap

case class EventTopicKey[E : PersistenceCodec](name: String) {
  val persistenceCodec: PersistenceCodec[E] = implicitly[PersistenceCodec[E]]
}

trait EventTopic[E] {

  def publish(event: E): Unit

  def followTail(listener: E => Unit)

  def processAtLeastOnce(processorId: String, batchSize: Int)(processor: E => Unit): Stream[IO, Int]
}

object EventTopic {

  private class TransientEventTopic[E] extends EventTopic[E] {
    val processors = new ConcurrentHashMap[String, E => Unit]

    override def publish(event: E): Unit = {
      processors.values().forEach((processor) => processor(event))
    }

    override def followTail(listener: E => Unit): Unit = {
      processors.putIfAbsent("", listener)
    }

    override def processAtLeastOnce(processorId: String, batchSize: Int)(processor: E => Unit): Stream[IO, Int] = {
      ???
    }
  }

  def transientEventTopic[E](): EventTopic[E] = new TransientEventTopic[E]
}