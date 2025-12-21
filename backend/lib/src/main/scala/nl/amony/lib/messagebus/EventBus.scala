package nl.amony.lib.messagebus

trait PersistentEventBus {

  def getTopic[E: EventTopicKey]: EventTopic[E] = getTopicForKey[E](implicitly[EventTopicKey[E]])

  def getTopicForKey[E](topic: EventTopicKey[E]): EventTopic[E]
}
