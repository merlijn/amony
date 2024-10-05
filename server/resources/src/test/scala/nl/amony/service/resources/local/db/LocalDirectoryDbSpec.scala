package nl.amony.service.resources.local.db

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import nl.amony.service.resources.api.ResourceInfo
import org.scalatest.flatspec.AnyFlatSpecLike
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api.*

class LocalDirectoryDbSpec extends AnyFlatSpecLike with Logging {

  import cats.effect.unsafe.implicits.global

  // we need to load the driver class or else we get a no suitable driver found exception
  Class.forName("org.h2.Driver")

  val config =
    """
      |h2mem1-test = {
      |  db {
      |    url = "jdbc:h2:mem:test1"
      |  }
      |
      |  driver = org.h2.Driver
      |
      |  connectionPool = disabled
      |  profile = "slick.jdbc.H2Profile$"
      |  keepAliveConnection = true
      |}
      |""".stripMargin

  val dbConfig = DatabaseConfig.forConfig[H2Profile]("h2mem1-test", ConfigFactory.parseString(config))

  val store = new LocalDirectoryDb(dbConfig)

  store.createTablesIfNotExists()

  def createResource(bucketId: String, resourceId: String = java.util.UUID.randomUUID().toString, tags: Seq[String] = Seq.empty): ResourceInfo = {
    ResourceInfo(
      bucketId = bucketId,
      path = "test",
      hash = resourceId,
      size = 1,
      contentType = None,
      tags = tags,
      creationTime = None,
      lastModifiedTime = None
    )
  }


  def randomId() = java.util.UUID.randomUUID().toString

  it should "insert a resource and retrieve it" in {

    val bucketId = "test-bucket"
    val resource = createResource(bucketId, randomId(), Seq("a", "b", "d"))

    store.insert(resource, IO.unit)

    val result = for {
      _         <- store.insert(resource, IO.unit)
      retrieved <- store.getByHash(bucketId, resource.hash)
    } yield {

      assert(retrieved == Some(resource))
    }

    result.unsafeRunSync()
  }

  it should "retrieve multiple resources by id" in {

    val bucketId = "multiple-get-by-id-test"

    val resource1 = createResource(bucketId, "1", Seq("a", "b"))
    val resource2 = createResource(bucketId, "2", Seq("c", "d", "e"))

    val result = for {
      _ <- store.insert(resource1, IO.unit)
      _ <- store.insert(resource2, IO.unit)
      retrieved <- store.getAllByIds(bucketId, Seq("1", "2"))
    } yield {

      assert(retrieved.toSet == Set(resource1, resource2))
    }

    result.unsafeRunSync()
  }

  it should "retrieve all resources for a bucket" in {

    val bucketId = "multiple-get-test"

    val resource1 = createResource(bucketId, "1", tags = Seq("a", "b"))
    val resource2 = createResource(bucketId, "2", tags = Seq("c", "d", "e"))

    val result = for {
      _         <- store.insert(resource1, IO.unit)
      _         <- store.insert(resource2, IO.unit)
      retrieved <- store.getAll(bucketId)
    } yield {
      assert(retrieved.toSet == Set(resource1, resource2))
    }

    result.unsafeRunSync()
  }

  it should "retrieve all child resources with tags" in {

    val bucketId = "child-tags-test"
    val parentId = "parent"

    val resourceA = createResource(bucketId, "1", tags = Seq("a", "b", "f")).copy(parentId = Some(parentId))
    val resourceB = createResource(bucketId, "2", tags = Seq("a", "c", "e")).copy(parentId = Some(parentId))

    val result = for {
      _ <- store.insert(resourceA, IO.unit)
      _ <- store.insert(resourceB, IO.unit)
      retrievedCommon <- store.getChildren(bucketId, parentId, Set("a"))
      retrievedA <- store.getChildren(bucketId, parentId, Set("b"))
      retrievedB <- store.getChildren(bucketId, parentId, Set("c"))
    } yield {
      assert(retrievedCommon.toSet == Set(resourceA, resourceB))
      assert(retrievedA.toSet == Set(resourceA))
      assert(retrievedB.toSet == Set(resourceB))
    }

    result.unsafeRunSync()
  }
}
