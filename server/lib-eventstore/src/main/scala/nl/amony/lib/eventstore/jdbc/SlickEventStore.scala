package nl.amony.lib.eventstore.jdbc

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.{Pull, Stream}
import nl.amony.lib.eventstore.{EventSourcedEntity, EventStore, PersistenceCodec}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.duration.DurationInt

case class EventRow(ord: Long,
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

    def            * = (ord, entityId, entityType, eventSeqNr, timestamp, serializerId, eventType, eventData) <> (EventRow.tupled, EventRow.unapply)
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

  private val pollInterval = 100.millis
  private val batchSize    = 1000

  val db = dbConfig.db

  def dbIO[T](a: slick.dbio.DBIOAction[T, NoStream, Nothing]): IO[T] =
    IO.fromFuture(IO(db.run(a))).onError { t => IO { logger.warn(t) } }

  def createIfNotExists(): IO[Unit] = for {
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

  private def latestSeqNrQuery(key: String) =
    eventTable
      .filter(_.entityId === key)
      .sortBy(_.eventSeqNr.desc)
      .take(1)

  private def eventQuery(entityId: String, start: Long, max: Option[Long]) = {
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

  override def get(entityId: String): EventSourcedEntity[S, E] =
    new EventSourcedEntity[S, E] {

      override def eventCount(): IO[Long] =
        dbIO(latestSeqNrQuery(entityId).result).map(_.headOption.map(_.sequenceNr).getOrElse(0L))

      def insertEntry(seqNr: Long, e: E) = {

        val (manifest, data) = eventCodec.encode(e)
//        logger.debug(s"Inserting entry ($seqNr): $e")
        eventTable += EventRow(0L, entityType, entityId, seqNr, System.currentTimeMillis(), eventCodec.getSerializerId(), manifest, data)
      }

      override def events(start: Long): Stream[IO, E] = {
        val query = eventQuery(entityId, start, None).result

        Stream
          .eval(dbIO(query))
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
          last  <- latestSeqNrQuery(entityId).result
          seqNr  = last.headOption.map(_.sequenceNr).getOrElse(-1L)
          _     <- insertEntry(seqNr + 1, e)
        } yield ()).transactionally

        dbIO(persistQuery).map(_ => initialState)
      }

      override def state(): IO[S] = {

        events()
          .compile
          .fold(initialState) { eventSourceFn }
      }

      override def follow(start: Long): Stream[IO, (E, S)] = ???

      override def update(fn: S => IO[E]): IO[S] = ???
    }

  override def delete(entityId: String): IO[Unit] = {
    val deleteEntries = eventTable.filter(_.entityId === entityId).delete

    dbIO(deleteEntries) >> IO.unit
  }

  override def followTail(): Stream[IO, (String, E)] = ???

  private def getEvents(start: Long, max: Long = batchSize) =
    eventTable
      .filter(_.ord >= start)
      .sortBy(_.ord.asc)
      .take(max)
      .result
      .map {
        _.map { row => row.entityId -> eventCodec.decode(row.eventType, row.eventData) }
      }

  def lastProcessorSeqNr(processorId: String) = processorTable
    .filter(_.processorId === processorId)
    .filter(_.entityType === entityType)
    .take(1).result.headOption.map {
      case Some((_, _, c)) => c
      case None            => 0L
    }

  def storeProcessorSeqNr(processorId: String, seqNr: Long) =
    processorTable.insertOrUpdate((processorId, entityType, seqNr))

  override def processAtLeastOnce(processorId: String, processorFn: (String, E) => Unit)(implicit runtime: IORuntime): Unit = {

    def processBatch(): IO[Int] =
      dbIO(lastProcessorSeqNr(processorId).flatMap { seqNr =>
        getEvents(seqNr).flatMap { events =>
          events.foreach {
            case (id, e) => processorFn(id, e)
          }

          storeProcessorSeqNr(processorId, seqNr + events.size)
        }
      })

    def pollRecursive(): Stream[IO, Int] = {
      Stream.sleep[IO](pollInterval)
        .flatMap(_ => Stream.eval(processBatch()))
        .flatMap(_ => pollRecursive())
    }

    pollRecursive().compile.drain.unsafeRunAndForget()
  }

  override def create(initialState: S): EventSourcedEntity[S, E] = ???
}
