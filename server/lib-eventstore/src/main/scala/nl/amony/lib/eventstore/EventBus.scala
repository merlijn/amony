package nl.amony.lib.eventstore

case class EventTopic[T](name: String)

trait EventBus {

  def listen[T](topic: EventTopic[T], listener: T => Unit)

  def processAtLeastOnce[T](topic: EventTopic[T], processorName: String, processor: T => Unit)
}
