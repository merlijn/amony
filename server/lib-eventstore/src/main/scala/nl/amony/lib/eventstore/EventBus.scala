package nl.amony.lib.eventstore

case class EventTopic[T](name: String)

trait EventBus {

  def listen[T](topic: EventTopic[T], listener: T => Unit)
}
