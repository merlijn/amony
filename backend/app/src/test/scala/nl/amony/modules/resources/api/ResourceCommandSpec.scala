package nl.amony.modules.resources.api

import io.circe.syntax.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.platform.messagebus.{Command, CommandId, CommandTopicKey}

class ResourceCommandSpec extends AnyWordSpecLike with Matchers:

  "ResourceCommand" should {

    "round-trip through its codec" in {
      val command: ResourceCommand = FixStreamability(BucketId("media"), ResourceId("abc123"))
      io.circe.parser.decode[ResourceCommand](command.asJson.noSpaces) shouldBe Right(command)
    }

    "round-trip inside the command envelope" in {
      val codec                             = summon[CommandTopicKey[ResourceCommand]].topicKey.codec
      val command: Command[ResourceCommand] = Command(CommandId("1"), "media:abc123", FixStreamability(BucketId("media"), ResourceId("abc123")))
      codec.decodeJson(command.asJson(using codec)).shouldBe(Right(command))
    }

    "derive the owning bucket from the message key" in {
      ResourceCommand.bucketOf("media:abc123") shouldBe Some(BucketId("media"))
      ResourceCommand.bucketOf("weird:id:abc123") shouldBe Some(BucketId("weird:id"))
      ResourceCommand.bucketOf("no-separator") shouldBe None
    }
  }
