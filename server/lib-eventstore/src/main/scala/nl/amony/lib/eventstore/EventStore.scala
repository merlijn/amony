package nl.amony.lib.eventstore

import cats.effect.IO
import fs2.Stream

trait EventStore[Key, S, E] {

  def index(): Stream[IO, Key]

  def getEvents(): Stream[IO, (Key, E)]

  def get(key: Key): EventSourcedEntity[S, E]

  def delete(id: Key): IO[Unit]

  def follow(): Stream[IO, (Key, E)]
}

trait EventSourcedEntity[S, E] {

  def eventCount(): IO[Long]

  def events(start: Long = 0L): Stream[IO, E]

  def followEvents(start: Long = 0L): Stream[IO, E]

  def follow(start: Long = 0L): Stream[IO, (E, S)]

  def persist(e: E): IO[S]

  def update(fn: S => IO[E]): IO[S]

  def state(): IO[S]
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


