package nl.amony.lib.eventbus.jdbc

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import fs2.Stream
import nl.amony.lib.eventbus.{EventTopicKey, PersistenceCodec}
import org.scalatest.flatspec.AnyFlatSpecLike
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.H2Profile

class SlickEventBusSpec extends AnyFlatSpecLike with Logging {

  trait TestEvent

  case class Added(msg: String) extends TestEvent
  case class Removed(msg: String) extends TestEvent

  implicit val eventCodec: PersistenceCodec[TestEvent] = new PersistenceCodec[TestEvent] {

    override def getSerializerId(): Long = 78L

    override def encode(e: TestEvent): (String, Array[Byte]) = e match {
      case Added(msg)   => e.getClass.getSimpleName -> msg.getBytes
      case Removed(msg) => e.getClass.getSimpleName -> msg.getBytes
    }

    override def decode(typeHint: String, bytes: Array[Byte]): TestEvent = typeHint match {
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

  val store = new SlickEventBus[H2Profile](dbConfig)
  store.createTablesIfNotExists().unsafeRunSync()

//  it should "do something" in {
//
//    def storeEvents(e: EventSourcedEntity[Set[String], TestEvent]) = {
//      for {
//        _ <- e.persist(Added("foo"))
//        _ <- e.persist(Removed("foo"))
//        _ <- e.persist(Added("bar"))
//        _ <- e.persist(Added("baz"))
//        s <- e.state()
//      } yield s
//    }
//
//    val sa = storeEvents(store.get("a")).unsafeRunSync()
//    val sb = storeEvents(store.get("b")).unsafeRunSync()
//
//    val index = store.index().compile.toList.unsafeRunSync()
//
//    println(index)
//  }

//  it should "follow a stream" ignore {
//
//    val entity = store.get("test")
//
//    // persist events
//    Stream.awakeEvery[IO](500.millis)
//      .flatMap(ts => Stream.eval(IO { println(s"Adding event at $ts") } >> entity.persist(Added(s"foo: ${ts}"))))
//      .compile.drain.unsafeRunAndForget()
//
//    // read events
//    entity.followEvents(0).foreach {
//      e => IO { println(s"received: $e") }
//    }.compile.drain.unsafeRunSync()
//  }

  it should "process at least once and continue where it left off" in {

    val processorId = "test-processor"

    val eventTopicKey = EventTopicKey[TestEvent]("test")
    val topic = store.getTopicForKey(eventTopicKey)

    def insertEvents(n: Int): Unit = {

      val events = (1 to n).map { i => Added(s"${i}") }

      Stream.fromIterator[IO](events.iterator, 1).evalMap {
        e => IO { topic.publish(e) }
      }.compile.drain.unsafeRunSync()
    }

    insertEvents(10)

    // read events
    val processingStream =
      topic.processAtLeastOnce(processorId, 1)(
        e => logger.info(s"processing: $e")
      )

    // take the first 5
    processingStream.take(5).compile.last.unsafeRunSync()
  }
}
