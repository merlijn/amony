package nl.amony.lib.eventbus

import scalapb.GeneratedMessage

trait PersistenceCodec[E] {

  def getSerializerId(): Long

  /**
   * Encodes the given object to a (typeHint, bytes) tuple
   *
   * @param e The object to serialize
   * @return
   */
  def encode(e: E): (String, Array[Byte])

  /**
   * Decodes a message
   *
   * @param typeHint indicates which type of message is contained in the data
   * @param bytes the data
   * @return
   */
  def decode(typeHint: String, bytes: Array[Byte]): E
}

object PersistenceCodec {

  import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

  def scalaPBPersistenceCodec[T <: GeneratedMessage](implicit cmp: GeneratedMessageCompanion[T]) =
    new PersistenceCodec[T] {
      override def getSerializerId(): Long = 3245172347L
      override def encode(e: T): (String, Array[Byte]) = e.getClass.getSimpleName -> cmp.toByteArray(e)
      override def decode(typeHint: String, bytes: Array[Byte]): T = cmp.parseFrom(bytes)
    }

  implicit def persistenceCodec[T <: GeneratedMessage](implicit cmp: GeneratedMessageCompanion[T]) =
    scalaPBPersistenceCodec[T](cmp)
}
