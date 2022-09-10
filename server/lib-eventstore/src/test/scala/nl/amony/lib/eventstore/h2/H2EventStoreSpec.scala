package nl.amony.lib.eventstore.h2

import com.typesafe.config.ConfigFactory
import monix.reactive.Consumer
import nl.amony.lib.eventstore.EventCodec
import org.scalatest.flatspec.AnyFlatSpecLike
import slick.jdbc.H2Profile

class H2EventStoreSpec extends AnyFlatSpecLike {

  trait TestEvent

  case class Added(msg: String) extends TestEvent
  case class Removed(msg: String) extends TestEvent

  implicit val eventCodec: EventCodec[TestEvent] = new EventCodec[TestEvent] {
    override def getManifest(e: TestEvent): String = e.getClass.getName
    override def encode(e: TestEvent): Array[Byte] = e match {
      case Added(msg)   => msg.getBytes
      case Removed(msg) => msg.getBytes
    }
    override def decode(manifest: String, bytes: Array[Byte]): TestEvent = manifest match {
      case "nl.amony.lib.eventstore.h2.H2EventStoreSpec$Added"   => Added(new String(bytes))
      case "nl.amony.lib.eventstore.h2.H2EventStoreSpec$Removed" => Removed(new String(bytes))
    }
  }


  def eventSourceFn(set: Set[String], e: TestEvent): Set[String] = e match {
    case Added(s)   => set + s
    case Removed(s) => set - s
  }

  import slick.jdbc.H2Profile.api._

  implicit val monixScheduler = monix.execution.Scheduler.Implicits.global

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

  val store = new H2EventStore[Set[String], TestEvent](db, eventSourceFn _, Set.empty)

  it should "do something" in {

    val e = store.get("test")

    store.createTables().runSyncUnsafe()

    val seq = for {
      _ <- e.persist(Added("foo"))
      _ <- e.persist(Removed("foo"))
      s <- e.current()
    } yield s

    seq.runSyncUnsafe()

    println(store.index().consumeWith(Consumer.toList[String]).runSyncUnsafe())

    val events: Seq[Any] = e.events().consumeWith(Consumer.toList[TestEvent]).runSyncUnsafe()

    println(events)
  }
}
