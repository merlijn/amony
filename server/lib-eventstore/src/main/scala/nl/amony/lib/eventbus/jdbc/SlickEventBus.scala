package nl.amony.lib.eventbus.jdbc

import cats.effect.IO
import cats.implicits._
import fs2.Stream
import nl.amony.lib.eventbus.{EventTopic, EventTopicKey, PersistentEventBus}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.duration.DurationInt

case class EventRow(ord: Option[Long],
                    topicId: String,
                    sequenceNr: Long,
                    timestamp: Long,
                    eventData: Array[Byte])

class SlickEventBus[P <: JdbcProfile](private val dbConfig: DatabaseConfig[P])
  extends PersistentEventBus with Logging {

  import dbConfig.profile.api._

  import scala.concurrent.ExecutionContext.Implicits.global

  private class Events(tag: Tag) extends Table[EventRow](tag, "events") {

    def ord          = column[Long]("ord", O.AutoInc)
    def topicId      = column[String]("topic_id")
    def timestamp    = column[Long]("timestamp")
    def eventData    = column[Array[Byte]]("event_data")
    def eventSeqNr   = column[Long]("event_seq_nr")

    def pk           = primaryKey("events_primary_key", (topicId, eventSeqNr))

    def            * = (ord.?, topicId, eventSeqNr, timestamp, eventData) <> (EventRow.apply.tupled, EventRow.unapply)
  }

  private class Processors(tag: Tag) extends Table[(String, String, Long)](tag, "processors") {
    def processorId  = column[String]("processor_id")
    def topicId      = column[String]("topic_id")
    def sequenceNr   = column[Long]("sequence_nr")
    def pk           = primaryKey("processors_primary_key", (processorId, topicId))
    def *            = (processorId, topicId, sequenceNr)
  }

  private val eventTable     = TableQuery[Events]
  private val processorTable = TableQuery[Processors]

  private val pollInterval     = 200.millis
  private val defaultBatchSize = 1000

  private val db = dbConfig.db

  def dbIO[T](a: slick.dbio.DBIOAction[T, NoStream, Nothing]): IO[T] =
    IO.fromFuture(IO(db.run(a))).onError { t => IO { logger.warn(t) } }

  def createTablesIfNotExists(): IO[Unit] =
    for {
      _ <- dbIO(eventTable.schema.createIfNotExists)
      _ <- dbIO(processorTable.schema.createIfNotExists)
    } yield ()

  def index(): Stream[IO, String] = {

    val query = eventTable.map(_.topicId).distinct.result
    val result: Stream[IO, String] =
      Stream
        .eval(dbIO(query))
        .flatMap(r => Stream.fromIterator[IO](r.iterator, 1))

    result
  }

  def getAllMessagesForTopic(topicId: String, start: Long, max: Option[Long]): Stream[IO, Array[Byte]] = {

    val query = queries.getEvents(topicId, start, max)

    Stream
      .eval(dbIO(query))
      .flatMap(r => Stream.fromIterator[IO](r.iterator, 1).map(_.eventData))
  }

  private object queries {

    def latestSeqNrQuery(entityId: String) =
      eventTable
        .filter(_.topicId === entityId)
        .sortBy(_.eventSeqNr.desc)
        .take(1)

    def getEvents(start: Long, max: Long = defaultBatchSize) =
      eventTable
        .filter(_.ord >= start)
        .sortBy(_.ord.asc)
        .take(max)
        .result


    def lastProcessedSeqNr(processorId: String, entityId: String) =
      processorTable
        .filter(_.processorId === processorId)
        .filter(_.topicId === entityId)
        .take(1).result.headOption.map {
          case Some((_, _, c)) => Some(c)
          case None => None
        }

    def storeProcessorSeqNr(processorId: String, entityId: String, seqNr: Long) =
      processorTable.insertOrUpdate((processorId, entityId, seqNr))

    def getEvents(topicId: String, start: Long, max: Option[Long]) = {
      val events = eventTable
        .filter(_.topicId === topicId)
        .filter(_.eventSeqNr >= start)
        .sortBy(_.ord.asc)

      val limited = max match {
        case None    => events
        case Some(n) => events.take(n)
      }

      limited.result
    }
  }

  override def getTopicForKey[E](topic: EventTopicKey[E]): EventTopic[E] = {

    implicit val ioRuntime = cats.effect.unsafe.implicits.global

    new EventTopic[E] {
      override def publish(e: E): IO[Unit] = {

        def insertEntry(seqNr: Long, e: E) = {
          val data = topic.persistenceCodec.encode(e)
          eventTable += EventRow(None, topic.name, seqNr, System.currentTimeMillis(), data)
        }

        val persistQuery = (for {
          last  <- queries.latestSeqNrQuery(topic.name).result
          seqNr  = last.headOption.map(_.sequenceNr).getOrElse(-1L)
          _     <- insertEntry(seqNr + 1, e)
        } yield ()).transactionally

        dbIO(persistQuery)
      }

      override def followTail(listener: E => Unit): Unit = ???

      override def processAtLeastOnce(processorId: String, batchSize: Int)(processorFn: E => IO[Unit]): Stream[IO, Int] = {

        def processBatch(): IO[Int] = {

          dbIO(queries.lastProcessedSeqNr(processorId, topic.name)).flatMap { optionalSeqNr =>

            val lastProcessedSeqNr: Long = optionalSeqNr.getOrElse(-1)

            dbIO(queries.getEvents(topic.name, lastProcessedSeqNr + 1, Some(batchSize)))
              .map {
                _.map { row => topic.persistenceCodec.decode(row.eventData) }
              }
              .flatMap { events =>

                events.foreach { e => processorFn(e) }

                val f: IO[Seq[Unit]] = events.map(e => processorFn(e)).sequence

                if (events.isEmpty)
                  IO(0)
                else
                  dbIO(queries.storeProcessorSeqNr(processorId, topic.name, lastProcessedSeqNr + events.size))
              }
          }
        }

        def processBatch2(): IO[Int] = {

          val transaction = (for {
            optionalSeqNr <- queries.lastProcessedSeqNr(processorId, topic.name)
            lastProcessedSeqNr = optionalSeqNr.getOrElse(-1L)
            events <- queries.getEvents(topic.name, lastProcessedSeqNr + 1, Some(batchSize)).map { _.map(row => topic.persistenceCodec.decode(row.eventData)) }
            _ <- DBIO.from(events.map(e => processorFn(e)).sequence.unsafeToFuture())
            n <- if (events.isEmpty) DBIO.successful(0) else queries.storeProcessorSeqNr(processorId, topic.name, lastProcessedSeqNr + events.size)
          } yield n).transactionally

          dbIO(transaction)
        }

        def pollBatchRecursive(): Stream[IO, Int] =
          Stream.sleep[IO](pollInterval) >> Stream.eval(processBatch2()) ++ pollBatchRecursive()

        pollBatchRecursive()
      }
    }
  }
}
