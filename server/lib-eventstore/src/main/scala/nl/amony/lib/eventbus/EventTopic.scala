package nl.amony.lib.eventbus

import cats.effect.IO
import fs2.Stream

case class EventTopicKey[E : PersistenceCodec](name: String) {
  val persistenceCodec: PersistenceCodec[E] = implicitly[PersistenceCodec[E]]
}

trait EventTopic[E] {

  def publish(event: E): Unit

  def followTail(listener: E => Unit)

  def processAtLeastOnce(processorId: String, batchSize: Int)(processor: E => Unit): Stream[IO, Int]
}