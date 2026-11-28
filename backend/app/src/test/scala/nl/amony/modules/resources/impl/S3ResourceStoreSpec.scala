package nl.amony.modules.resources.impl

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import fs2.Stream
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import nl.amony.modules.resources.api.*

class S3ResourceStoreSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

  private val accessKey = "rustfsadmin"
  private val secretKey = "rustfsadmin"
  private val region    = "us-east-1"

  override val containerDef: GenericContainer.Def[GenericContainer] =
    GenericContainer.Def(
      "rustfs/rustfs:1.0.7",
      exposedPorts = Seq(9000),
      env          = Map("RUSTFS_ACCESS_KEY" -> accessKey, "RUSTFS_SECRET_KEY" -> secretKey),
      waitStrategy = Wait.forHttp("/health").forPort(9000)
    )

  private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
  private val httpClient  = HttpClient.newHttpClient()

  private def endpointOf(container: GenericContainer): URI =
    URI.create(s"http://${container.containerIpAddress}:${container.mappedPort(9000)}")

  private def adminClient(container: GenericContainer): S3Client =
    S3Client.builder()
      .region(Region.of(region))
      .endpointOverride(endpointOf(container))
      .credentialsProvider(credentials)
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .build()

  private def withBucket[A](container: GenericContainer, prefix: Option[String] = None)(
    fn: (String, S3Client, S3ResourceStore) => IO[A]
  ): A =
    val bucket = s"amony-test-${UUID.randomUUID().toString.take(8)}"
    val client = adminClient(container)
    try
      client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
      val config = S3StorageConfig(
        bucket          = bucket,
        region          = region,
        endpoint        = Some(endpointOf(container)),
        prefix          = prefix,
        pathStyleAccess = true,
        presignedUrlTtl = 5.minutes,
        accessKeyId     = Some(accessKey),
        secretAccessKey = Some(secretKey)
      )
      S3ResourceStore.resource(config).use(store => fn(bucket, client, store)).unsafeRunSync()
    finally client.close()

  private def fetchBytes(uri: URI): (Int, Option[String], Array[Byte]) =
    val request  = HttpRequest.newBuilder(uri).GET().build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
    val ct       = Option(response.headers().firstValue("Content-Type").orElse(null))
    (response.statusCode(), ct, response.body())

  "S3ResourceStore" should {

    "derive resource-id paths for uploads and fresh paths for replacements" in withContainers { container =>
      withBucket(container) { (_, _, store) =>
        val id = ResourceId("abc123xyz")
        IO {
          store.pathForUpload(id, "holiday video.mp4") shouldBe ResourcePath("abc123xyz.mp4")
          store.pathForUpload(id, "README") shouldBe ResourcePath("abc123xyz")
          val replacement = store.pathForReplacement(ResourcePath("abc123xyz.mp4"), id)
          replacement shouldNot be(ResourcePath("abc123xyz.mp4"))
          replacement should startWith("abc123xyz_")
          replacement should endWith(".mp4")
        }
      }
    }

    "store a resource, return a working presigned redirect, and stat it" in withContainers { container =>
      withBucket(container) { (_, _, store) =>
        val key   = StoreKey.Resource(ResourcePath("nested/video.mp4"))
        val bytes = "video-payload".getBytes(StandardCharsets.UTF_8)

        for
          putRes  <- store.put(key, Some("video/mp4"), Stream.emits(bytes)).value
          _        = putRes shouldBe Right(())
          exists  <- store.exists(key)
          _        = exists shouldBe true
          content <- store.content(key, Some("video/mp4"), Some("etag-ignored"))
          _        = content match
                       case Some(ResourceContent.Redirect(uri, expiresAt)) =>
                         expiresAt.isAfter(Instant.now()) shouldBe true
                         val (status, ct, body) = fetchBytes(URI.create(uri.renderString))
                         status shouldBe 200
                         ct shouldBe Some("video/mp4")
                         body shouldBe bytes
                       case other                                          => fail(s"Expected Redirect, got $other")
          source  <- store.openSource(ResourcePath("nested/video.mp4"))
          _        = source match
                       case Some(MediaSource.RemoteUrl(uri)) =>
                         val (status, _, body) = fetchBytes(uri)
                         status shouldBe 200
                         body shouldBe bytes
                       case other                            => fail(s"Expected RemoteUrl, got $other")
          st      <- store.stat(ResourcePath("nested/video.mp4"))
          _        = st.map(_.path) shouldBe Some(ResourcePath("nested/video.mp4"))
          _        = st.map(_.size) shouldBe Some(bytes.length.toLong)
          _        = st.flatMap(_.fileKey).isDefined shouldBe true
        yield ()
      }
    }

    "upload streams larger than the single-part threshold using multipart upload" in withContainers { container =>
      withBucket(container) { (_, _, store) =>
        val totalBytes = S3ResourceStore.MultipartPartSize + (1024 * 1024)
        val payload    = Array.tabulate[Byte](totalBytes)(i => (i % 251).toByte)
        val key        = StoreKey.Resource(ResourcePath("large.bin"))

        for
          putRes <- store.put(key, Some("application/octet-stream"), Stream.emits(payload)).value
          _       = putRes shouldBe Right(())
          st     <- store.stat(ResourcePath("large.bin"))
          _       = st.map(_.size) shouldBe Some(totalBytes.toLong)
          source <- store.openSource(ResourcePath("large.bin"))
          _       = source match
                      case Some(MediaSource.RemoteUrl(uri)) =>
                        val (status, _, body) = fetchBytes(uri)
                        status shouldBe 200
                        body shouldBe payload
                      case other                            => fail(s"Expected RemoteUrl, got $other")
        yield ()
      }
    }

    "return None for missing objects and invalid paths" in withContainers { container =>
      withBucket(container) { (_, _, store) =>
        for
          missingContent <- store.content(StoreKey.Resource(ResourcePath("missing.mp4")), None, None)
          invalidContent <- store.content(StoreKey.Resource(ResourcePath("/abs.mp4")), None, None)
          missingSource  <- store.openSource(ResourcePath("missing.mp4"))
          invalidSource  <- store.openSource(ResourcePath("../escape.mp4"))
          missingStat    <- store.stat(ResourcePath("missing.mp4"))
        yield
          missingContent shouldBe None
          invalidContent shouldBe None
          missingSource shouldBe None
          invalidSource shouldBe None
          missingStat shouldBe None
      }
    }

    "store and delete derived artifacts scoped to a resource id" in withContainers { container =>
      withBucket(container) { (_, _, store) =>
        val keep = StoreKey.Derived(ResourceId("res2"), "res2_v1_w512.webp")
        val del1 = StoreKey.Derived(ResourceId("res1"), "res1_v1_w512.webp")
        val del2 = StoreKey.Derived(ResourceId("res1"), "res1_v1.vtt")

        for
          _          <- store.putBytes(keep, Some("image/webp"), "keep".getBytes(StandardCharsets.UTF_8))
          _          <- store.putBytes(del1, Some("image/webp"), "del1".getBytes(StandardCharsets.UTF_8))
          _          <- store.putBytes(del2, Some("text/vtt"), "WEBVTT\n".getBytes(StandardCharsets.UTF_8))
          _          <- store.deleteDerived(ResourceId("res1"))
          del1Exists <- store.exists(del1)
          del2Exists <- store.exists(del2)
          keepExists <- store.exists(keep)
        yield
          del1Exists shouldBe false
          del2Exists shouldBe false
          keepExists shouldBe true
      }
    }

    "delete a stored resource and refuse invalid paths" in withContainers { container =>
      withBucket(container) { (_, _, store) =>
        val key = StoreKey.Resource(ResourcePath("delete-me.mp4"))

        for
          _      <- store.putBytes(key, Some("video/mp4"), "bye".getBytes(StandardCharsets.UTF_8))
          _      <- store.delete(key)
          exists <- store.exists(key)
          _       = exists shouldBe false
          _      <- store.delete(StoreKey.Resource(ResourcePath("non-existent.mp4")))
          err    <- store.delete(StoreKey.Resource(ResourcePath("../escape.mp4"))).attempt
          _       = err.left.toOption.get shouldBe a[IllegalArgumentException]
        yield ()
      }
    }

    "isolate objects when configured with a prefix" in withContainers { container =>
      withBucket(container, prefix = Some("tenant-a")) { (bucket, _, storeA) =>
        val configB = S3StorageConfig(
          bucket          = bucket,
          region          = region,
          endpoint        = Some(endpointOf(container)),
          prefix          = Some("tenant-b"),
          pathStyleAccess = true,
          accessKeyId     = Some(accessKey),
          secretAccessKey = Some(secretKey)
        )
        S3ResourceStore.resource(configB).use { storeB =>
          val key = StoreKey.Resource(ResourcePath("same.mp4"))
          for
            _       <- storeA.putBytes(key, Some("video/mp4"), "from-a".getBytes(StandardCharsets.UTF_8))
            existsA <- storeA.exists(key)
            existsB <- storeB.exists(key)
          yield
            existsA shouldBe true
            existsB shouldBe false
        }
      }
    }
  }
