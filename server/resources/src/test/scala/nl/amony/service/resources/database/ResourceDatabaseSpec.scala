package nl.amony.service.resources.database

import com.typesafe.config.ConfigFactory
import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta, ResourceMetaSource}
import org.scalatest.flatspec.AnyFlatSpecLike
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api.*

class ResourceDatabaseSpec extends AnyFlatSpecLike with Logging {

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

  val store = new ResourceDatabase(dbConfig)
  store.init()

  def createResource(bucketId: String, resourceId: String = java.util.UUID.randomUUID().toString, tags: Set[String] = Set.empty): ResourceInfo = {
    ResourceInfo(
      bucketId = bucketId,
      path = "test",
      userId = "0",
      resourceId = resourceId,
      size = 1,
      hash = Some(resourceId),
      contentType = None,
      tags = tags,
      creationTime = None,
      lastModifiedTime = None
    )
  }


  def randomId() = java.util.UUID.randomUUID().toString

  it should "insert a resource and retrieve it" in {

    val bucketId = "test-bucket"
    val resource = createResource(bucketId, randomId(), Set("a", "b", "d"))

    store.insert(resource)

    val result = for {
      _         <- store.insert(resource)
      retrieved <- store.getByResourceId(bucketId, resource.resourceId)
      tagLabels <- store.getAllTagLabels()
    } yield {
      assert(tagLabels == Set("a", "b", "d"))
      assert(retrieved == Some(resource))
    }

    result.unsafeRunSync()
  }

  it should "update a resource" in {

    val bucketId = "update-resource-test"
    val resourceId = randomId()
    val resourceOriginal = createResource(bucketId, resourceId, Set("a"))
    val resourceUpdated = resourceOriginal.copy(tags = Set("b", "c"), description = Some("updated"))

    val result = for {
      _ <- store.upsert(resourceOriginal)
      _ <- store.upsert(resourceUpdated)
      retrieved <- store.getByResourceId(bucketId, resourceId)
    } yield {

      assert(retrieved == Some(resourceUpdated))
    }

    result.unsafeRunSync()
  }

  it should "updating a resource with the same value should have no effect (idempotent)" in {

    val bucketId = "update-same-resource-test"
    val resourceId = randomId()
    val resourceOriginal = createResource(bucketId, resourceId, tags = Set("c", "d", "e")).copy(contentMetaSource = Some(ResourceMetaSource("tool", "data")))

    val result = for {
      _ <- store.upsert(resourceOriginal)
      retrieved <- store.getByResourceId(bucketId, resourceId)
      _ <- store.upsert(retrieved.get)
      retrievedAgain <- store.getByResourceId(bucketId, resourceId)
    } yield {
      assert(retrievedAgain == Some(resourceOriginal))
    }

    result.unsafeRunSync()
  }

  it should "retrieve multiple resources by id" in {

    val bucketId = "multiple-get-by-id-test"

    val resource1 = createResource(bucketId, "1", Set("a", "b"))
    val resource2 = createResource(bucketId, "2", Set("c", "d", "e"))

    val result = for {
      _ <- store.insert(resource1)
      _ <- store.insert(resource2)
      retrieved <- store.getAllByIds(bucketId, Seq("1", "2"))
    } yield {

      assert(retrieved.toSet == Set(resource1, resource2))
    }

    result.unsafeRunSync()
  }

  it should "retrieve all resources for a bucket" in {

    val bucketId = "multiple-get-test"

    val resource1 = createResource(bucketId, "1", tags = Set("a", "b"))
    val resource2 = createResource(bucketId, "2", tags = Set("c", "d", "e")).copy(contentMetaSource = Some(ResourceMetaSource("tool", "data")))

    val result = for {
      _         <- store.insert(resource1)
      _         <- store.insert(resource2)
      retrieved <- store.getAll(bucketId)
    } yield {
      assert(retrieved.toSet == Set(resource1, resource2))
    }

    result.unsafeRunSync()
  }
}
