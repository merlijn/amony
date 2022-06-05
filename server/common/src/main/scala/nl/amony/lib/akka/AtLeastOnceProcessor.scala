package nl.amony.lib.akka

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.query.scaladsl.EventsByPersistenceIdQuery
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.stream.{Materializer, SystemMaterializer}

import scala.reflect.ClassTag

object AtLeastOnceProcessor {

  case class Processed(sequenceNr: Long)
  case class ProcessedState(highestSequenceNr: Long)

  def eventHandler(state: ProcessedState, event: Processed): ProcessedState = state.copy(highestSequenceNr = event.sequenceNr)

  def process[E : ClassTag](persistenceId: String, processor: E => Unit): Behavior[(Long, E)] = {
    Behaviors.setup[(Long, E)] { context =>
      val readJournalId = context.system.settings.config.getString("amony.akka.read-journal")
      val readJournal = PersistenceQuery(context.system).readJournalFor[EventsByPersistenceIdQuery](readJournalId)
      process(persistenceId, readJournal, processor)
    }
  }

  def process[E : ClassTag](persistenceId: String, readJournal: EventsByPersistenceIdQuery, processor: E => Unit) = {

    Behaviors.setup[(Long, E)] { context =>
      val classTag = implicitly[ClassTag[E]]
      implicit val mat: Materializer = SystemMaterializer.get(context.system).materializer

      EventSourcedBehavior.apply[(Long, E), Processed, ProcessedState](
        persistenceId  = PersistenceId.ofUniqueId(s"$persistenceId-processor"),
        emptyState     = ProcessedState(0),
        commandHandler = commandHandler(processor),
        eventHandler   = eventHandler
      ).receiveSignal {
        case (ProcessedState(fromSequenceNr), RecoveryCompleted) =>
          // start processing on startup from previous highest seq nr
          readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, Long.MaxValue).runForeach {
            case EventEnvelope(_, _, sequenceNr, classTag(e))  => context.self.tell(sequenceNr -> e)
          }
      }
    }
  }

  def commandHandler[E](processor: E => Unit): (ProcessedState, (Long, E)) => Effect[Processed, ProcessedState] = {

    (state, cmd) => {
      cmd match {
        case (seq, e) =>
          processor(e)
          Effect.persist(Processed(seq))
      }
    }
  }
}
