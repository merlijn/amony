package nl.amony.lib.eventstore.jdbc

import cats.effect.IO
import fs2.{Pull, Stream}
import nl.amony.lib.eventstore.{EventSourcedEntity, EventStore, PersistenceCodec}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt

case class EventRow(ord: Long,
                    entityId: String,
                    sequenceNr: Long,
                    timestamp: Long,
                    serializerId: Long,
                    eventType: String,
                    eventData: Array[Byte])

class SlickEventStore[P <: JdbcProfile, S, E : PersistenceCodec](private val dbConfig: DatabaseConfig[P], eventSourceFn: (S, E) => S, initialState: S) extends EventStore[String, S, E] with Logging {

  import dbConfig.profile.api._

  import scala.concurrent.ExecutionContext.Implicits.global

  private class Events(tag: Tag) extends Table[EventRow](tag, "events") {

    def ord          = column[Long]("ord", O.AutoInc)
    def id           = column[String]("entity_id")
    def sequenceNr   = column[Long]("sequence_nr")
    def timestamp    = column[Long]("timestamp")
    def serializerId = column[Long]("serializer_id")
    def eventType    = column[String]("type")
    def eventData    = column[Array[Byte]]("data")
    def pk           = primaryKey("primary_key", (id, sequenceNr))

    def            * = (ord, id, sequenceNr, timestamp, serializerId, eventType, eventData) <> (EventRow.tupled, EventRow.unapply)
  }

  private class Snapshots(tag: Tag) extends Table[(String, Long, Array[Byte])](tag, "events") {
    def id           = column[String]("key", O.PrimaryKey) // This is the primary key column
    def sequenceNr   = column[Long]("sequence_nr")
    def serializerId = column[Long]("serializer_id")
    def eventType    = column[String]("type")
    def data         = column[Array[Byte]]("data")
    def *            = (id, sequenceNr, data)
  }

  private class Listeners(tag: Tag) extends Table[(String, Long)](tag, "consumers") {

    def consumer_id = column[String]("listener_id")
    def sequenceNr  = column[Long]("sequence_nr")

    def * = (consumer_id, sequenceNr)
  }

  private val eventTable = TableQuery[Events]
  private val snapshotTable = TableQuery[Snapshots]

  private val eventCodec = implicitly[PersistenceCodec[E]]

  private val pollInterval = 100.millis

  val db = dbConfig.db

  def databaseIO[T](a: slick.dbio.DBIOAction[T, NoStream, Nothing]): IO[T] =
    IO.fromFuture(IO(db.run(a)))
      .onError { t => IO { logger.warn(t) } }

  def createIfNotExists(): IO[Unit] = for {
    _ <- databaseIO(eventTable.schema.createIfNotExists)
    _ <- databaseIO(snapshotTable.schema.createIfNotExists)
  } yield ()

  override def getEvents(): Stream[IO, (String, E)] = ???

  override def index(): Stream[IO, String] = {

    val query = eventTable.map(_.id).distinct.result
    val result: Stream[IO, String] =
      Stream
        .eval(databaseIO(query))
        .flatMap(r => Stream.fromIterator[IO](r.iterator, 1))

    result
  }

  override def get(key: String): EventSourcedEntity[S, E] =
    new EventSourcedEntity[S, E] {

      def latestSeqNrQuery() =
        eventTable
          .filter(_.id === key)
          .sortBy(_.sequenceNr.desc)
          .take(1)

      override def eventCount(): IO[Long] =
        databaseIO(latestSeqNrQuery().result).map(_.headOption.map(_.sequenceNr).getOrElse(0L))

      def insertEntry(seqNr: Long, e: E) = {

        val (manifest, data) = eventCodec.encode(e)
        logger.debug(s"Inserting entry ($seqNr): $e")
        eventTable += EventRow(0L, key, seqNr, System.currentTimeMillis(), eventCodec.getSerializerId(), manifest, data)
      }

      override def events(start: Long): Stream[IO, E] = {
        val query = eventTable
          .filter(_.id === key)
          .filter(_.sequenceNr >= start)
          .sortBy(_.sequenceNr.asc)
          .map(row => row.eventType -> row.eventData).result

        Stream
          .eval(databaseIO(query))
          .flatMap(result => Stream.fromIterator[IO](result.iterator, 1))
          .map { case (manifest, data) => eventCodec.decode(manifest, data) }
      }

      override def followEvents(start: Long): Stream[IO, E] = {

        val current = events(start)

        current ++ current.zipWithIndex.last.map {
          case None           => start
          case Some((_, idx)) => start + idx + 1
        }.flatMap { lastSeq =>
          // this recursion is stack safe because IO.flatmap is stack safe
          Stream.sleep[IO](pollInterval).flatMap(_ => followEvents(lastSeq))
        }
      }

      override def persist(e: E): IO[S] = {

        val persistQuery = (for {
          last  <- latestSeqNrQuery().result
          seqNr  = last.headOption.map(_.sequenceNr).getOrElse(-1L)
          _     <- insertEntry(seqNr + 1, e)
        } yield ()).transactionally

        databaseIO(persistQuery).map(_ => initialState)
      }

      override def state(): IO[S] = {

        events()
          .compile
          .fold(initialState) { eventSourceFn }
      }

      override def follow(start: Long): Stream[IO, (E, S)] = ???

      override def update(fn: S => IO[E]): IO[S] = ???
    }

  override def delete(id: String): IO[Unit] = {
    val deleteEntries = eventTable.filter(_.id === id).delete

    databaseIO(deleteEntries) >> IO.unit
  }

  override def follow(): Stream[IO, (String, E)] = ???
}
