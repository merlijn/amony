package nl.amony.service.resources.database

import cats.effect.IO
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import cats.effect.unsafe.implicits.global
import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta}
import org.scalatest.matchers.should.Matchers
import scribe.Logging
import cats.implicits.*

import java.util.UUID
import scala.util.Random

class ResourceDatabaseSpec extends AnyWordSpecLike with TestContainerForAll with Logging with Matchers {

  override val containerDef: GenericContainer.Def[GenericContainer] =
    GenericContainer.Def(
      "postgres:17.2",
      exposedPorts = Seq(5432),
      waitStrategy = Wait.forLogMessage(".*database system is ready to accept connections.*", 2),
      env = Map(
        "POSTGRES_USER" -> "test",
        "PGUSER" -> "test",
        "POSTGRES_PASSWORD" -> "test",
        "POSTGRES_DB" -> "test"
      )
    )

  val row =
    ResourceRow(
      bucket_id = "bucket",
      resource_id = "resource",
      user_id = "user",
      relative_path = "path",
      hash = "hash",
      size = 0,
      content_type = None,
      content_meta_tool_name = None,
      content_meta_tool_data = None,
      creation_time = None,
      last_modified_time = None,
      title = None,
      description = None
    )

  val alphabet = ('a' to 'z').toList

  def randomTag = alphabet(Random.nextInt(alphabet.size)).toString

  def genResource(): ResourceInfo =
    ResourceInfo(
      bucketId = UUID.randomUUID().toString,
      resourceId = UUID.randomUUID().toString,
      userId = UUID.randomUUID().toString,
      path = "path",
      hash = Some("hash"),
      size = 0,
      contentType = None,
      contentMetaSource = None,
      contentMeta = ResourceMeta.Empty,
      tags = Set.fill(Random.nextInt(10))(randomTag),
      creationTime = None,
      lastModifiedTime = None,
      title = None,
      description = None,
      thumbnailTimestamp = None
    )

  "The Database" should {
    "insert a resource row and retrieve it" in {
      withContainers { container =>

        logger.info(s"Container IP: ${container.containerIpAddress}")
        logger.info(s"Container Port: ${container.mappedPort(5432)}")

        val dbConfig = DatabaseConfig(
          host = container.containerIpAddress,
          port = container.mappedPort(5432),
          database = "test",
          user = "test",
          password = Some("test")
        )

        ResourceDatabase.make(dbConfig).use(db =>
          insertResource(db) // *> insertRow(db)
          
        ).unsafeRunSync()
      }
    }
  }

  def insertResource(db: ResourceDatabase): IO[Unit] = {

    def identityTest(resource: ResourceInfo) =
      for {
        _        <- db.insertResource(resource)
        returned <- db.getById(resource.bucketId, resource.resourceId)
      } yield {
        Some(resource) shouldBe returned
      }

    (List.fill(100)(genResource()).map(identityTest)).sequence >> IO.unit
  }

  def insertTags(db: ResourceDatabase): IO[Unit] =
    for {
      _    <- db.tables.tags.upsert(Set("a", "b", "c"))
      _    <- db.tables.tags.upsert(Set("a", "b", "c", "d", "e"))
      tags <- db.tables.tags.all
    } yield {
      logger.info(s"tags: ${tags.mkString(", ")}")
      tags.map(_.label) should contain theSameElementsAs Set("a", "b", "c", "d", "e")
    }
  
  
  def insertRow(db: ResourceDatabase): IO[Unit] =
    for {
      _           <- db.tables.resources.insert(row)
      returnedRow <- db.tables.resources.getById(row.bucket_id, row.resource_id)
    } yield logger.info(s"returned row: $returnedRow")
}
