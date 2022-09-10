package nl.amony.service.media

import com.typesafe.config.ConfigFactory
import nl.amony.service.media.actor.MediaLibEventSourcing
import nl.amony.service.media.actor.MediaLibEventSourcing.{MediaAdded, MediaRemoved}
import nl.amony.service.media.actor.MediaLibProtocol._
import scribe.Logging
import slick.jdbc.H2Profile

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

//object MediaService {
//
//  val mediaPersistenceId = "mediaLib"
//
//  def behavior(): Behavior[MediaCommand] =
//    ServiceBehaviors.setupAndRegister[MediaCommand] { context =>
//
//      implicit val ec = context.executionContext
//      implicit val sc = context.system.scheduler
//
//      EventSourcedBehavior[MediaCommand, Event, State](
//        persistenceId  = PersistenceId.ofUniqueId(mediaPersistenceId),
//        emptyState     = State(Map.empty),
//        commandHandler = MediaLibCommandHandler(),
//        eventHandler   = MediaLibEventSourcing.apply
//      )
//    }
//}

class MediaService()  extends Logging {

  import slick.jdbc.H2Profile.api._

  import scala.concurrent.ExecutionContext.Implicits.global

  val config =
    """
      |h2mem1-test = {
      |  url = "jdbc:h2:mem:test1"
      |  driver = org.h2.Driver
      |  connectionPool = disabled
      |  keepAliveConnection = true
      |}
      |""".stripMargin

  val db: H2Profile.backend.Database = Database.forConfig("h2mem1-test", ConfigFactory.parseString(config))

  val mediaRepository = new MediaRepository(db)

  var eventListener: MediaLibEventSourcing.Event => Unit = _ => ()

  def setEventListener(listener: MediaLibEventSourcing.Event => Unit) = {
    eventListener = listener
  }

  def init() = Await.result(mediaRepository.createTables(), 5.seconds)

  def getById(id: String): Future[Option[Media]] = {
    mediaRepository.getById(id)
//    ask[MediaCommand, Option[Media]](ref => GetById(id, ref))
  }

  def getAll(): Future[List[Media]] = {
    Future.successful(List.empty)
//    ask[MediaCommand, List[Media]](ref => GetAll(ref))
  }

  def exportToJson(): Future[String] = {

    Future.successful("")

//    val objectMapper = JacksonObjectMapperProvider.get(system).getOrCreate("media-export", None)
//
//    getAll().map { medias =>
//      val out = new ByteArrayOutputStream()
//      objectMapper.createGenerator(out, JsonEncoding.UTF8).useDefaultPrettyPrinter().writeObject(medias)
//      out.toString()
//    }
  }

  def upsertMedia(media: Media): Future[Media] = {
    mediaRepository.upsert(media).map { media =>
      eventListener.apply(MediaAdded(media))
      media
    }
//    ask[MediaCommand, Boolean](ref => UpsertMedia(media, ref)).map(_ => media)
  }

  def deleteMedia(id: String, deleteResource: Boolean): Future[Boolean] = {
    mediaRepository.deleteById(id).map { n =>
      logger.info(s"Media removed: ${id}")
      eventListener(MediaRemoved(id))
      n > 0
    }
//    ask[MediaCommand, Boolean](ref => RemoveMedia(id, deleteResource, ref))
  }

  def updateMetaData(id: String, title: Option[String], comment: Option[String], tags: List[String]): Future[Either[ErrorResponse, Media]] = {

    mediaRepository.getById(id).flatMap {
      case None        => Future.successful(Left(MediaNotFound(id)))
      case Some(media) => mediaRepository.upsert(media.copy(meta = MediaMeta(title, comment, tags.toSet))).map(Right(_))
    }

    // ask[MediaCommand, Either[ErrorResponse, Media]](ref => UpdateMetaData(id, title, comment, tags.toSet, ref))
  }
}
