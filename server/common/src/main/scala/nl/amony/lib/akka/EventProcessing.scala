package nl.amony.lib.akka

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.query.scaladsl.EventsByPersistenceIdQuery
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.stream.{Materializer, SystemMaterializer}

import scala.reflect.ClassTag

object EventProcessing {

  case class ProcessEventCmd[E](sequenceNr: Long, event: E)
  case class Processed(sequenceNr: Long)
  case class ProcessedState(highestSequenceNr: Long)

  def processAtLeastOnce[E : ClassTag](persistenceId: String, processorName: String, processor: E => Unit): Behavior[ProcessEventCmd[E]] = {
    Behaviors.setup[ProcessEventCmd[E]] { context =>
      val readJournalId = context.system.settings.config.getString("akka.persistence.query.journal.plugin-id")
      val readJournal = PersistenceQuery(context.system).readJournalFor[EventsByPersistenceIdQuery](readJournalId)
      process(persistenceId, processorName, readJournal, processor)
    }
  }

  private def process[E : ClassTag](persistenceId: String, processorName: String, readJournal: EventsByPersistenceIdQuery, processor: E => Unit): Behavior[ProcessEventCmd[E]] = {

    Behaviors.setup[ProcessEventCmd[E]] { context =>
      val classTag = implicitly[ClassTag[E]]
      implicit val mat: Materializer = SystemMaterializer.get(context.system).materializer

      EventSourcedBehavior.apply[ProcessEventCmd[E], Processed, ProcessedState](
        persistenceId  = PersistenceId.ofUniqueId(s"$persistenceId-$processorName"),
        emptyState     = ProcessedState(0),
        commandHandler = (_, cmd) => { processor(cmd.event); Effect.persist(Processed(cmd.sequenceNr)) },
        eventHandler   = (state, event) => state.copy(highestSequenceNr = event.sequenceNr)
      ).receiveSignal {
        case (ProcessedState(fromSequenceNr), RecoveryCompleted) =>
          // start processing on startup from previous highest seq nr
          readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr + 1, Long.MaxValue).runForeach {
            case EventEnvelope(_, _, sequenceNr, classTag(e))  => context.self.tell(ProcessEventCmd(sequenceNr, e))
          }
      }
    }
  }
}
