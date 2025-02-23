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

  val alphabet = ('a' to 'z').toList

  def randomTag = alphabet(Random.nextInt(alphabet.size)).toString

  def randomString = Random.alphanumeric.take(8).mkString
  def nextTimestamp = Math.max(0, Random.nextInt())

  def genResource(): ResourceInfo =
    ResourceInfo(
      bucketId = UUID.randomUUID().toString,
      resourceId = UUID.randomUUID().toString,
      userId = UUID.randomUUID().toString,
      path = randomString,
      hash = Some(randomString),
      size = Random.nextLong(),
      contentType = None,
      contentMetaSource = None,
      contentMeta = ResourceMeta.Empty, // this field is not stored
      tags = Set.fill(Random.nextInt(5))(randomTag),
      creationTime = Some(nextTimestamp),
      lastModifiedTime = Some(nextTimestamp),
      title = Some(randomString),
      description = Some(randomString),
      thumbnailTimestamp = Some(nextTimestamp)
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
          username = "test",
          password = Some("test")
        )

        ResourceDatabase.make(dbConfig).use(db =>
          insertResourcesTest(db) >> updateUserMetaTest(db) >> uniquePathConstraint(db) >> db.truncateTables()

        ).unsafeRunSync()
      }
    }
  }

  def uniquePathConstraint(db: ResourceDatabase): IO[Unit] = {
    val resourceA  = genResource()
    val resourceB = genResource().copy(bucketId = resourceA.bucketId, path = resourceA.path)
    for {
      _ <- db.insertResource(resourceA)
      _ <- db.insertResource(resourceB)
    } yield ()
  }

  def updateUserMetaTest(db: ResourceDatabase): IO[Unit] = {

    val resource = genResource()
    val updated = resource.copy(
      title = Some("new title"),
      description = Some("new description"),
      tags = Set("update-a", "update-b", "update-c")
    )

    for {
      _      <- db.insertResource(resource)
      _      <- db.updateUserMeta(resource.bucketId, resource.resourceId, updated.title, updated.description, updated.tags.toList)
      result <- db.getById(resource.bucketId, resource.resourceId)
    } yield {
      result.flatMap(_.title) shouldBe updated.title
      result.flatMap(_.description) shouldBe updated.description
      result.map(_.tags) shouldBe Some(updated.tags)
    }
  }

  def insertResourcesTest(db: ResourceDatabase): IO[Unit] = {

    def insertIdentityCheck(resource: ResourceInfo) =
      for {
        _        <- db.insertResource(resource)
        returned <- db.getById(resource.bucketId, resource.resourceId)
      } yield {
        Some(resource) shouldBe returned
      }

    def upsertIdentityCheck(resource: ResourceInfo) =
      for {
        _        <- db.upsert(resource)
        returned <- db.getById(resource.bucketId, resource.resourceId)
      } yield {
        Some(resource) shouldBe returned
      }
      
    def deleteCheck(resourceInfo: ResourceInfo) = 
      for {
        _      <- db.deleteResource(resourceInfo.bucketId, resourceInfo.resourceId)
        result <- db.getById(resourceInfo.bucketId, resourceInfo.resourceId)
      } yield {
        result shouldBe None
      }  

    def validateAll(expected: List[ResourceInfo]) =
      db.getAll("test").map { inserted =>
        logger.info(s"validated: ${inserted.size}")
        inserted should contain theSameElementsAs expected
      }

    val inserted  = List.fill(32)(genResource().copy(bucketId = "test"))
    val updated   = inserted.map(orig => genResource().copy(bucketId = orig.bucketId, resourceId = orig.resourceId))
    val shuffled  = scala.util.Random.shuffle(updated)
    val toDelete  = shuffled.take(16)
    val remaining = shuffled.drop(16)

    val insertChecks = inserted.map(insertIdentityCheck).sequence >> validateAll(inserted)
    val upsertChecks = updated.map(upsertIdentityCheck).sequence >> validateAll(updated)
    val deleteChecks = toDelete.map(deleteCheck).sequence >> validateAll(remaining)

    insertChecks >> upsertChecks >> deleteChecks >> IO.unit
  }

  def insertTags(db: ResourceDatabase): IO[Unit] =
    for {
      _    <- db.tables.tags.upsert(List("a", "b", "c"))
      _    <- db.tables.tags.upsert(List("a", "b", "c", "d", "e"))
      tags <- db.tables.tags.all
    } yield {
      logger.info(s"tags: ${tags.mkString(", ")}")
      tags.map(_.label) should contain theSameElementsAs List("a", "b", "c", "d", "e")
    }
}
