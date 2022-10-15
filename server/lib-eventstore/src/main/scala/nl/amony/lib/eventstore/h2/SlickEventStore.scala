package nl.amony.lib.eventstore.h2

import monix.eval.Task
import monix.reactive.Observable
import nl.amony.lib.eventstore.{EventCodec, EventSourcedEntity, EventStore}
import scala.concurrent.Future
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

case class EventEntry(ord: Long, id: String, sequenceNr: Long, timestamp: Long, eventType: String, eventData: Array[Byte])

class SlickEventStore[P <: JdbcProfile, S, E : EventCodec](private val dbConfig: DatabaseConfig[P], fn: (S, E) => S, initialState: S) extends EventStore[String, S, E] {

  import dbConfig.profile.api._

  private class Events(tag: Tag) extends Table[EventEntry](tag, "events") {

    def ord        = column[Long]("ord", O.AutoInc)
    def id         = column[String]("key") // This is the primary key column
    def sequenceNr = column[Long]("sequence_nr")
    def timestamp  = column[Long]("timestamp")
    def eventType  = column[String]("type")
    def eventData  = column[Array[Byte]]("data")

    def pk         = primaryKey("primary_key", (id, sequenceNr))

    def          * = (ord, id, sequenceNr, timestamp, eventType, eventData) <> (EventEntry.tupled, EventEntry.unapply)
  }

  private class Snapshots(tag: Tag) extends Table[(String, Long, Array[Byte])](tag, "events") {
    def id         = column[String]("key", O.PrimaryKey) // This is the primary key column
    def sequenceNr = column[Long]("sequence_nr")
    def data       = column[Array[Byte]]("data")
    def *          = (id, sequenceNr, data)
  }

  private val eventTable = TableQuery[Events]
  private val eventCodec = implicitly[EventCodec[E]]

  val db = dbConfig.db

  import scala.concurrent.ExecutionContext.Implicits.global

  def createTables(): Task[Unit] =
    Task.fromFuture(db.run(eventTable.schema.create))

  override def getEvents(): Observable[(String, E)] = ???

  override def index(): Observable[String] = {
    val query = eventTable.map(_.id).distinct.result
    val result = db.run(query).map(_.iterator)
    Observable.fromIteratorF[Future, String](result)
  }

  override def get(key: String): EventSourcedEntity[S, E] =
    new EventSourcedEntity[S, E] {

      def latestSeqNr() =
        eventTable
          .filter(_.id === key)
          .sortBy(_.sequenceNr.asc)
          .take(1)

      def insertEntry(seqNr: Long, e: E) =
        eventTable += EventEntry(0L, key, seqNr, System.currentTimeMillis(), eventCodec.getManifest(e), eventCodec.encode(e))

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

        val persistQuery = (for {
          last  <- latestSeqNr().result
          seqNr  = last.headOption.map(_.sequenceNr).getOrElse(0L)
          _     <- insertEntry(seqNr + 1, e)
        } yield ()).transactionally

        Task.fromFuture(db.run(persistQuery)).map(_ => initialState)
      }

      override def current(): Task[S] = Task.now(initialState)

      override def follow(start: Long): Observable[(E, S)] = ???
    }

  override def delete(id: String): Task[Unit] = {
    val query = eventTable.filter(_.id === id).delete
    Task.fromFuture(db.run(query)).map(_ => ())
  }
}
