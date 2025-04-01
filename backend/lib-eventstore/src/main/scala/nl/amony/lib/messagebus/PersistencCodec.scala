package nl.amony.lib.messagebus

import scalapb.TypeMapper


trait PersistenceCodec[E] {

  /**
   * Encodes the given object to a (typeHint, bytes) tuple
   *
   * @param e The object to serialize
   * @return
   */
  def encode(e: E): Array[Byte]

  /**
   * Decodes a message
   *
   * @param typeHint indicates which type of message is contained in the data
   * @param bytes the data
   * @return
   */
  def decode(bytes: Array[Byte]): E
}

object PersistenceCodec:

  import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

  def scalaPBPersistenceCodec[T <: GeneratedMessage](using cmp: GeneratedMessageCompanion[T]): PersistenceCodec[T] =
    new PersistenceCodec[T]:
      override def encode(e: T) = cmp.toByteArray(e)
      override def decode(bytes: Array[Byte]): T = cmp.parseFrom(bytes)

  def scalaPBMappedPersistenceCodec[A <: GeneratedMessage, B](using tm: TypeMapper[A, B], cmp: GeneratedMessageCompanion[A]): PersistenceCodec[B] =
    val codec = scalaPBPersistenceCodec[A]
    new PersistenceCodec[B]: 
      override def encode(msg: B) = codec.encode(tm.toBase(msg))
      override def decode(bytes: Array[Byte]): B = tm.toCustom(codec.decode(bytes))
