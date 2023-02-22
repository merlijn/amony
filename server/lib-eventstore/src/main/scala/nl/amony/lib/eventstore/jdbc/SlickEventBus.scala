package nl.amony.lib.eventstore.jdbc

import nl.amony.lib.eventstore.{EventTopic, PersistenceCodec}

class SlickEventTopic[E : PersistenceCodec](topicId: String) extends EventTopic[E] {

  override def followTail(listener: E => Unit): Unit = ???

  override def processAtLeastOnce(processorName: String, processor: E => Unit): Unit = ???
}

class SlickEventBus {

}
