package nl.amony.lib.eventstore.jdbc

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.{Pull, Stream}
import nl.amony.lib.eventstore.{EventSourcedEntity, EventStore, PersistenceCodec}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.duration.DurationInt

case class EventRow(ord: Option[Long],
                    entityType: String,
                    entityId: String,
                    sequenceNr: Long,
                    timestamp: Long,
                    serializerId: Long,
                    eventType: String,
                    eventData: Array[Byte])

class SlickEventStore[P <: JdbcProfile, S, E : PersistenceCodec](private val dbConfig: DatabaseConfig[P], entityType: String, eventSourceFn: (S, E) => S, initialState: S) extends EventStore[S, E] with Logging {

  import dbConfig.profile.api._

  import scala.concurrent.ExecutionContext.Implicits.global

  private class Events(tag: Tag) extends Table[EventRow](tag, "events") {

    def ord          = column[Long]("ord", O.AutoInc)
    def entityId     = column[String]("entity_id")
    def entityType   = column[String]("entity_type")
    def timestamp    = column[Long]("timestamp")
    def serializerId = column[Long]("serializer_id")
    def eventType    = column[String]("event_type")
    def eventData    = column[Array[Byte]]("event_data")
    def eventSeqNr   = column[Long]("event_seq_nr")

    def pk           = primaryKey("events_primary_key", (entityId, eventType, eventSeqNr))

    def            * = (ord.?, entityType, entityId, eventSeqNr, timestamp, serializerId, eventType, eventData) <> (EventRow.tupled, EventRow.unapply)
  }

  private class Snapshots(tag: Tag) extends Table[(String, Long, Array[Byte])](tag, "events") {
    def id           = column[String]("key", O.PrimaryKey)
    def sequenceNr   = column[Long]("sequence_nr")
    def serializerId = column[Long]("serializer_id")
    def eventType    = column[String]("type")
    def data         = column[Array[Byte]]("data")
    def *            = (id, sequenceNr, data)
  }

  private class Processors(tag: Tag) extends Table[(String, String, Long)](tag, "processors") {
    def processorId  = column[String]("processor_id")
    def entityType   = column[String]("entity_type")
    def sequenceNr   = column[Long]("sequence_nr")
    def pk           = primaryKey("processors_primary_key", (processorId, entityType))
    def *            = (processorId, entityType, sequenceNr)
  }

  private val eventTable     = TableQuery[Events]
  private val snapshotTable  = TableQuery[Snapshots]
  private val processorTable = TableQuery[Processors]

  private val eventCodec = implicitly[PersistenceCodec[E]]

  private val pollInterval     = 200.millis
  private val defaultBatchSize = 1000

  val db = dbConfig.db

  def dbIO[T](a: slick.dbio.DBIOAction[T, NoStream, Nothing]): IO[T] =
    IO.fromFuture(IO(db.run(a))).onError { t => IO { logger.warn(t) } }

  def createIfNotExists(): IO[Unit] =
    for {
      _ <- dbIO(eventTable.schema.createIfNotExists)
      _ <- dbIO(snapshotTable.schema.createIfNotExists)
      _ <- dbIO(processorTable.schema.createIfNotExists)
    } yield ()

  override def getEvents(): Stream[IO, (String, E)] = ???

  override def index(): Stream[IO, String] = {

    val query = eventTable.map(_.entityId).distinct.result
    val result: Stream[IO, String] =
      Stream
        .eval(dbIO(query))
        .flatMap(r => Stream.fromIterator[IO](r.iterator, 1))

    result
  }

  private object queries {

    def latestSeqNrQuery(entityId: String) =
      eventTable
        .filter(_.entityId === entityId)
        .sortBy(_.eventSeqNr.desc)
        .take(1)

    def getEvents(start: Long, max: Long = defaultBatchSize) =
      eventTable
        .filter(_.ord >= start)
        .sortBy(_.ord.asc)
        .take(max)
        .result
        .map {
          _.map { row => row.entityId -> eventCodec.decode(row.eventType, row.eventData) }
        }

    def lastProcessedSeqNr(processorId: String) =
      processorTable
        .filter(_.processorId === processorId)
        .filter(_.entityType === entityType)
        .take(1).result.headOption.map {
        case Some((_, _, c)) => Some(c)
        case None => None
      }

    def storeProcessorSeqNr(processorId: String, seqNr: Long) =
      processorTable.insertOrUpdate((processorId, entityType, seqNr))

    def getEvents(entityId: String, start: Long, max: Option[Long]) = {
      val events = eventTable
        .filter(_.entityId === entityId)
        .filter(_.eventSeqNr >= start)
        .sortBy(_.eventSeqNr.asc)
        .map(row => row.eventType -> row.eventData)

      max match {
        case None    => events
        case Some(n) => events.take(n)
      }
    }
  }

  override def get(entityId: String): EventSourcedEntity[S, E] =
    new EventSourcedEntity[S, E] {

      override def eventCount(): IO[Long] =
        dbIO(queries.latestSeqNrQuery(entityId).result).map(_.headOption.map(_.sequenceNr).getOrElse(0L))

      def insertEntry(seqNr: Long, e: E) = {
        val (manifest, data) = eventCodec.encode(e)
        eventTable += EventRow(None, entityType, entityId, seqNr, System.currentTimeMillis(), eventCodec.getSerializerId(), manifest, data)
      }

      override def events(start: Long): Stream[IO, E] = {
        Stream
          .eval(dbIO(queries.getEvents(entityId, start, None).result))
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
          Stream.sleep[IO](pollInterval) >> followEvents(lastSeq)
        }
      }

      override def persist(e: E): IO[S] = {

        val persistQuery = (for {
          last  <- queries.latestSeqNrQuery(entityId).result
          seqNr  = last.headOption.map(_.sequenceNr).getOrElse(-1L)
          _     <- insertEntry(seqNr + 1, e)
        } yield ()).transactionally

        dbIO(persistQuery).map(_ => initialState)
      }

      override def state(): IO[S] =
        events()
          .compile
          .fold(initialState) { eventSourceFn }

      override def follow(start: Long): Stream[IO, (E, S)] = ???

      override def update(fn: S => IO[E]): IO[S] = ???
    }

  override def delete(entityId: String): IO[Unit] = {
    val deleteEntries = eventTable.filter(_.entityId === entityId).delete

    dbIO(deleteEntries) >> IO.unit
  }

  override def followTail(): Stream[IO, (String, E)] = ???

  override def processAtLeastOnce(processorId: String, batchSize: Int)(processorFn: (String, E) => Unit): Stream[IO, Int] = {

    def processBatch(batchSize: Int): IO[Int] = {

      dbIO(queries.lastProcessedSeqNr(processorId)).flatMap { optionalSeqNr =>

        val lastProcessedSeqNr: Long = optionalSeqNr.getOrElse(0)

        dbIO(queries.getEvents(lastProcessedSeqNr + 1, batchSize)).flatMap { events =>
          events.foreach { case (id, e) => processorFn(id, e) }

          if (events.isEmpty)
            IO(0)
          else
            dbIO(queries.storeProcessorSeqNr(processorId, lastProcessedSeqNr + events.size))
        }
      }
    }

    def pollBatchRecursive(): Stream[IO, Int] =
      Stream.sleep[IO](pollInterval) >> Stream.eval(processBatch(batchSize)) ++ pollBatchRecursive()

    pollBatchRecursive()
  }

  override def create(initialState: S): EventSourcedEntity[S, E] = ???
}
