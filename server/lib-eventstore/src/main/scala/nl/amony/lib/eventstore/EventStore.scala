package nl.amony.lib.eventstore

import cats.Id
import cats.effect.IO
import fs2.Stream

trait EventStore[Key, S, E] {

  /**
   * Returns all keys
   *
   * @return
   */
  def index(): Stream[IO, Key]

  def getEvents(): Stream[IO, (Key, E)]

  def get(key: Key): EventSourcedEntity[S, E]

  def delete(id: Key): IO[Unit]
}

trait EventSourcedEntity[S, E] {

  def events(start: Long = 0L): Stream[IO, E]

  def followEvents(start: Long = 0L): Stream[IO, E]

  def follow(start: Long = 0L): Stream[IO, (E, S)]

  def persist(e: E): IO[S]

  def current(): IO[S]
}

trait EventCodec[E] {

  def getManifest(e: E): String

  def encode(e: E): Array[Byte]

  def decode(manifest: String, bytes: Array[Byte]): E
}


