package nl.amony.lib.eventstore

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream

sealed trait ProcessorEvent[E]

trait EventStore[S, E] {

  def index(): Stream[IO, String]

  def getEvents(): Stream[IO, (String, E)]

  def create(initialState: S): EventSourcedEntity[S, E]

  def get(key: String): EventSourcedEntity[S, E]

  def delete(id: String): IO[Unit]

  def followTail(): Stream[IO, (String, E)]

  def processAtLeastOnce(processorId: String, processor: (String, E) => Unit)(implicit runtime: IORuntime)
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


