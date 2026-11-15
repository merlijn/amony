package nl.amony.modules.resources.api

import io.circe.syntax.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.auth.api.UserId
import nl.amony.platform.messagebus.{Job, JobId, JobTopicKey}

class ResourceJobSpec extends AnyWordSpecLike with Matchers:

  "ResourceJob" should {

    "round-trip through its codec" in {
      val job: ResourceJob = FixStreamability(BucketId("media"), ResourceId("abc123"))
      io.circe.parser.decode[ResourceJob](job.asJson.noSpaces) shouldBe Right(job)
    }

    "round-trip inside the job envelope" in {
      val codec                 = summon[JobTopicKey[ResourceJob]].topicKey.codec
      val job: Job[ResourceJob] = Job(JobId("1"), "media:abc123", FixStreamability(BucketId("media"), ResourceId("abc123")))
      codec.decodeJson(job.asJson(using codec)).shouldBe(Right(job))
    }

    "round-trip an upload job through its codec" in {
      val job: ResourceJob = UploadResource(
        bucketId   = BucketId("media"),
        resourceId = ResourceId("abc123"),
        userId     = UserId("admin"),
        fileName   = "video.mp4",
        tempFile   = "abc123-upload"
      )
      io.circe.parser.decode[ResourceJob](job.asJson.noSpaces) shouldBe Right(job)
    }

    "derive the owning bucket from the message key" in {
      ResourceJob.bucketOf("media:abc123") shouldBe Some(BucketId("media"))
      ResourceJob.bucketOf("weird:id:abc123") shouldBe Some(BucketId("weird:id"))
      ResourceJob.bucketOf("no-separator") shouldBe None
    }
  }
