package nl.amony.service.resources.local

import com.typesafe.config.ConfigFactory
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.local.db.LocalDirectoryDb
import org.scalatest.flatspec.AnyFlatSpecLike
import slick.basic.DatabaseConfig
import slick.jdbc.H2Profile

class LocalDirectoryDbSpec extends AnyFlatSpecLike {

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

  val dbConfig = DatabaseConfig.forConfig[H2Profile]("h2mem1-test", ConfigFactory.parseString(config))

  val localDirectoryConfig = LocalDirectoryConfig(
    id = "test",
    path = java.nio.file.Path.of("/tmp"),
    scanParallelFactor = 4,
    verifyExistingHashes = false,
    hashingAlgorithm = ???,
    relativeResourcePath = ???,
    extensions = ???
  )

  val store = new LocalDirectoryDb(dbConfig)

  it should "do something" in {
    println(store)
  }
}
