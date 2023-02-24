package nl.amony.lib.eventstore

case class EventTopicKey[T](name: String)

sealed trait DeliveryGuarantee

case object AtMostOnce extends DeliveryGuarantee
case object AtLeastOnce extends DeliveryGuarantee
case object ExactlyOnce extends DeliveryGuarantee

trait EventTopic[E] {

  def publish(event: E): Unit

  def followTail(listener: E => Unit)

  def processAtLeastOnce(processorName: String, processor: E => Unit)
}

trait EventBus {

  def getTopic[E : EventTopicKey]: EventTopic[E] = getTopicForKey[E](implicitly[EventTopicKey[E]])

  def getTopicForKey[E](topic: EventTopicKey[E]): EventTopic[E]
}
