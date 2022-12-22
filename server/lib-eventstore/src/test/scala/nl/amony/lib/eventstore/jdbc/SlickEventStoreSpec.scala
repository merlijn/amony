package nl.amony.lib.eventstore.jdbc

import cats.effect.IO
import fs2.Stream
import com.typesafe.config.ConfigFactory
import nl.amony.lib.eventstore.{EventSourcedEntity, PersistenceCodec}
import org.scalatest.flatspec.AnyFlatSpecLike
import slick.basic.DatabaseConfig
import slick.jdbc.H2Profile

import scala.concurrent.duration.DurationInt

class SlickEventStoreSpec extends AnyFlatSpecLike {

  trait TestEvent

  case class Added(msg: String) extends TestEvent
  case class Removed(msg: String) extends TestEvent

  implicit val eventCodec: PersistenceCodec[TestEvent] = new PersistenceCodec[TestEvent] {

    override def getSerializerId(): Long = 78L

    override def encode(e: TestEvent): (String, Array[Byte]) = e match {
      case Added(msg)   => e.getClass.getSimpleName -> msg.getBytes
      case Removed(msg) => e.getClass.getSimpleName -> msg.getBytes
    }

    override def decode(manifest: String, bytes: Array[Byte]): TestEvent = manifest match {
      case "Added"   => Added(new String(bytes))
      case "Removed" => Removed(new String(bytes))
    }
  }

  def eventSourceFn(set: Set[String], e: TestEvent): Set[String] = e match {
    case Added(s)   => set + s
    case Removed(s) => set - s
  }

  val config =
    """
      |h2mem1-test = {
      |  db {
      |    url = "jdbc:h2:mem:test1"
      |  }
      |
      |  connectionPool = disabled
      |  profile = "slick.jdbc.H2Profile$"
      |  keepAliveConnection = true
      |}
      |""".stripMargin

  import cats.effect.unsafe.implicits.global
  val dbConfig = DatabaseConfig.forConfig[H2Profile]("h2mem1-test", ConfigFactory.parseString(config))

  val store = new SlickEventStore[H2Profile, Set[String], TestEvent](dbConfig, eventSourceFn _, Set.empty)
  store.createIfNotExists().unsafeRunSync()

  it should "do something" in {

    def storeEvents(e: EventSourcedEntity[Set[String], TestEvent]) = {
      for {
        _ <- e.persist(Added("foo"))
        _ <- e.persist(Removed("foo"))
        _ <- e.persist(Added("bar"))
        _ <- e.persist(Added("baz"))
        s <- e.state()
      } yield s
    }

    val sa = storeEvents(store.get("a")).unsafeRunSync()
    val sb = storeEvents(store.get("b")).unsafeRunSync()

    val index = store.index().compile.toList.unsafeRunSync()

    println(index)
  }

  it should "follow a stream" in {

    val entity = store.get("test")

    // persist events
    Stream.awakeEvery[IO](500.millis)
      .flatMap(ts => Stream.eval(IO { println(s"Adding event at $ts") } >> entity.persist(Added(s"foo: ${ts}"))))
      .compile.drain.unsafeRunAndForget()

    // read events
    entity.followEvents(0).foreach {
      e => IO { println(s"received: $e") }
    }.compile.drain.unsafeRunSync()
  }
}
