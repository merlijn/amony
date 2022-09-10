package nl.amony.lib.eventstore.h2

import monix.eval.Task
import monix.reactive.Observable
import nl.amony.lib.eventstore.{EventCodec, EventSourcedEntity, EventStore}
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api._

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Future

// Definition of the SUPPLIERS table
class Events(tag: Tag) extends Table[(Long, String, Long, Long, String, Array[Byte])](tag, "events") {

  def ord        = column[Long]("ord", O.AutoInc)
  def id         = column[String]("key") // This is the primary key column
  def sequenceNr = column[Long]("sequence_nr")
  def timestamp  = column[Long]("timestamp")
  def eventType  = column[String]("type")
  def eventData  = column[Array[Byte]]("data")

  def pk         = primaryKey("primary_key", (id, sequenceNr))

  def          * = (ord, id, sequenceNr, timestamp, eventType, eventData)
}

class Snapshots(tag: Tag) extends Table[(String, Long, Array[Byte])](tag, "events") {
  def id         = column[String]("key", O.PrimaryKey) // This is the primary key column
  def sequenceNr = column[Long]("sequence_nr")
  def data       = column[Array[Byte]]("data")
  def *          = (id, sequenceNr, data)
}


class H2EventStore[S, E : EventCodec](db: H2Profile.backend.Database, fn: (S, E) => S, initialState: S) extends EventStore[String, S, E] {
  val eventTable = TableQuery[Events]
  val eventCodec = implicitly[EventCodec[E]]

  import scala.concurrent.ExecutionContext.Implicits.global

  def createTables() = Task.fromFuture(db.run(eventTable.schema.create))

  override def getEvents(): Observable[(String, E)] = ???

  override def index(): Observable[String] = {
    val query = eventTable.map(_.id).distinct.result
    val result = db.run(query).map(_.iterator)
    Observable.fromIteratorF[Future, String](result)
  }

  override def get(key: String): EventSourcedEntity[S, E] =
    new EventSourcedEntity[S, E] {

      val seq = new AtomicLong(0)

      override def events(start: Long): Observable[E] = {
        val query = eventTable
          .filter(_.id === key)
          .filter(_.sequenceNr >= start)
          .sortBy(_.sequenceNr.asc)
          .map(row => row.eventType -> row.eventData).result

        val future = db.run(query).map(_.iterator)
        Observable.fromIteratorF(future).map { case (manifest, data) => eventCodec.decode(manifest, data) }
      }

      override def followEvents(start: Long): Observable[E] = ???

      override def persist(e: E): Task[S] = {
        val query = eventTable += (0L, key, seq.incrementAndGet(), System.currentTimeMillis(), eventCodec.getManifest(e), eventCodec.encode(e))

        Task.fromFuture(db.run(query)).map(_ => initialState)
      }

      override def current(): Task[S] = Task.now(initialState)

      override def follow(start: Long): Observable[(E, S)] = ???
    }

  override def delete(id: String): Task[Unit] = {
    val query = eventTable.filter(_.id === id).delete
    Task.fromFuture(db.run(query)).map(_ => ())
  }
}
