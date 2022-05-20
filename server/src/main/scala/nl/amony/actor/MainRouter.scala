package nl.amony.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.Materializer
import nl.amony.AmonyConfig
import nl.amony.search.SearchProtocol._
import nl.amony.actor.media.MediaApi
import nl.amony.actor.resources.MediaScanner
import nl.amony.actor.resources.ResourceApi
import nl.amony.search.InMemoryIndex
import nl.amony.search.SearchApi.searchServiceKey
import nl.amony.user.AuthApi

object MainRouter {

  def apply(config: AmonyConfig, scanner: MediaScanner): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val mat = Materializer(context)

      val readJournal =
        PersistenceQuery(context.system).readJournalFor[LeveldbReadJournal]("akka.persistence.query.journal.leveldb")

      val localIndexRef: ActorRef[QueryMessage] = InMemoryIndex.apply(context, readJournal)

      val resourceRef = context.spawn(ResourceApi.resourceBehaviour(config.media, scanner), "resources")
      val mediaRef    = context.spawn(MediaApi.mediaBehaviour(config.media, resourceRef), "medialib")
      val userRef     = context.spawn(AuthApi.userBehaviour(), "users")

      Behaviors.empty
    }
}
