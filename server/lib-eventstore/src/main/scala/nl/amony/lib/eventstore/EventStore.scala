package nl.amony.lib.eventstore

import cats.effect.IO
import fs2.Stream

sealed trait ProcessorEvent[E]

trait EventStore[S, E] {

  def index(): Stream[IO, String]

  def getEvents(): Stream[IO, (String, E)]

  def get(key: String): EventSourcedEntity[S, E]

  def delete(id: String): IO[Unit]

  def followTail(): Stream[IO, (String, E)]

  def processAtLeastOnce(processorId: String, processor: (String, E) => IO[Unit])
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


