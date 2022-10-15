package nl.amony.lib.eventstore.h2

import cats.Id
import cats.effect.IO
import fs2.{Pure, Stream}
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

  def createTables(): IO[Unit] =
    IO.fromFuture(IO(db.run(eventTable.schema.create)))

  override def getEvents(): Stream[IO, (String, E)] = ???

  override def index(): Stream[IO, String] = {

    val query = eventTable.map(_.id).distinct.result
    val result: Stream[IO, String] =
      Stream
        .eval(IO.fromFuture(IO(db.run(query))))
        .flatMap(r => Stream.fromIterator[IO](r.iterator, 1))

    result
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

      override def events(start: Long): Stream[IO, E] = {
        val query = eventTable
          .filter(_.id === key)
          .filter(_.sequenceNr >= start)
          .sortBy(_.sequenceNr.asc)
          .map(row => row.eventType -> row.eventData).result

        Stream
          .eval(IO.fromFuture(IO(db.run(query))))
          .flatMap(result => Stream.fromIterator[IO](result.iterator, 1))
          .map { case (manifest, data) => eventCodec.decode(manifest, data) }
      }

      override def followEvents(start: Long): Stream[IO, E] = ???

      override def persist(e: E): IO[S] = {

        val persistQuery = (for {
          last  <- latestSeqNr().result
          seqNr  = last.headOption.map(_.sequenceNr).getOrElse(0L)
          _     <- insertEntry(seqNr + 1, e)
        } yield ()).transactionally

        IO.fromFuture(IO(db.run(persistQuery))).map(_ => initialState)
      }

      override def current(): IO[S] = IO.pure(initialState)

      override def follow(start: Long): Stream[IO, (E, S)] = ???
    }

  override def delete(id: String): IO[Unit] = {
    val query = eventTable.filter(_.id === id).delete
    IO.fromFuture(IO(db.run(query))).map(_ => ())
  }
}
