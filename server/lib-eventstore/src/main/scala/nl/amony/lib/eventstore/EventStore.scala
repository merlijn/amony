package nl.amony.lib.eventstore

import cats.effect.IO
import fs2.Stream

sealed trait ProcessorEvent[E]

//case class EntityDeleted(key: Key)

trait EventStore[Key, S, E] {

  def index(): Stream[IO, Key]

  def getEvents(): Stream[IO, (Key, E)]

  def get(key: Key): EventSourcedEntity[S, E]

  def delete(id: Key): IO[Unit]

  def follow(): Stream[IO, (Key, E)]

  def processAtLeastOnce(processorId: String, processor: (Key, E) => IO[Unit])
}

trait PersistenceCodec[E] {

  def getSerializerId(): Long

  /**
   * Encodes the given object to a (typeHint, bytes) tuple
   *
   * @param e The object to serialize
   * @return
   */
  def encode(e: E): (String, Array[Byte])

  def decode(manifest: String, bytes: Array[Byte]): E
}


