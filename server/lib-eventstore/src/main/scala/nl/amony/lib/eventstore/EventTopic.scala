package nl.amony.lib.eventstore

case class EventTopicKey[T](name: String)

sealed trait DeliveryGaruantee

case object AtMostOnce extends DeliveryGaruantee
case object AtLeastOnce extends DeliveryGaruantee
case object ExactlyOnce extends DeliveryGaruantee

trait EventTopic[E] {

  def followTail(listener: E => Unit)

  def processAtLeastOnce(processorName: String, processor: E => Unit)
}

trait EventBus {
  def getTopic[E](topic: EventTopicKey[E]): EventTopic[E]
}
