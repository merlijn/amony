package nl.amony.lib.eventstore

import monix.eval.Task
import monix.reactive.Observable

trait EventStore[Key, S, E] {

  /**
   * Returns all keys
   *
   * @return
   */
  def index(): Observable[Key]

  def getEvents(): Observable[(Key, E)]

  def get(key: Key): EventSourcedEntity[S, E]

  def delete(id: Key): Task[Unit]
}

trait EventSourcedEntity[S, E] {

  def events(start: Long = 0L): Observable[E]

  def followEvents(start: Long = 0L): Observable[E]

  def follow(start: Long = 0L): Observable[(E, S)]

  def persist(e: E): Task[S]

  def current(): Task[S]
}

trait EventCodec[E] {

  def getManifest(e: E): String

  def encode(e: E): Array[Byte]

  def decode(manifest: String, bytes: Array[Byte]): E
}


