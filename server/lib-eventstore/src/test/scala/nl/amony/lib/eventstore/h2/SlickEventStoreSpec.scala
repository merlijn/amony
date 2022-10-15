package nl.amony.lib.eventstore.h2

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import nl.amony.lib.eventstore.EventCodec
import org.scalatest.flatspec.AnyFlatSpecLike
import slick.basic.DatabaseConfig
import slick.jdbc.H2Profile

class SlickEventStoreSpec extends AnyFlatSpecLike {

  trait TestEvent

  case class Added(msg: String) extends TestEvent
  case class Removed(msg: String) extends TestEvent

  implicit val eventCodec: EventCodec[TestEvent] = new EventCodec[TestEvent] {
    override def getManifest(e: TestEvent): String = e.getClass.getSimpleName
    override def encode(e: TestEvent): Array[Byte] = e match {
      case Added(msg)   => msg.getBytes
      case Removed(msg) => msg.getBytes
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

  it should "do something" in {

    val e = store.get("test")

    store.createTables().unsafeRunSync()

    val seq = for {
      _ <- e.persist(Added("foo"))
      _ <- e.persist(Removed("foo"))
      s <- e.current()
    } yield s

    seq.unsafeRunSync()

    val index = store.index().compile.toList.unsafeRunSync()

    println(index)

    val events: Seq[Any] = e.events().compile.toList.unsafeRunSync()

    println(events)
  }
}
