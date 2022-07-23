package nl.amony.service.resources.local

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.SystemMaterializer
import akka.util.Timeout
import nl.amony.lib.files.PathOps
import nl.amony.service.media.MediaConfig.LocalResourcesConfig
import nl.amony.service.resources.ResourceProtocol._
import nl.amony.service.resources.local.LocalResourcesStore.{DeleteFileByHash, LocalResourceCommand}
import nl.amony.service.resources.local.tasks.CreatePreviews
import scribe.Logging

import scala.concurrent.duration.DurationInt

object LocalResourcesHandler extends Logging {

  implicit val monixScheduler = monix.execution.Scheduler.Implicits.global

  def apply(config: LocalResourcesConfig, store: ActorRef[LocalResourceCommand]): Behavior[ResourceCommand] = {

    Behaviors.receive { (context, msg) =>

      implicit val mat = SystemMaterializer.get(context.system).materializer
      implicit val scheduler = context.system.scheduler
      implicit val askTimeout = Timeout(5.seconds)

      msg match {

        case DeleteResource(resourceHash, sender) =>

          store.tell(DeleteFileByHash(resourceHash, sender))
          Behaviors.same

        case CreateFragment(media, range, overwrite, sender) =>
          logger.info(s"Creating fragment: ${media.id}-$range")
          CreatePreviews.createVideoPreview(config, media, range, overwrite).executeAsync.runAsync { result =>
            sender.tell(result.isRight)
          }
          Behaviors.same

        case CreateFragments(media, overwrite) =>

          CreatePreviews.createVideoPreviews(config, media, overwrite).executeAsync.runAsyncAndForget
//          LocalResourcesTasks.createPreviewSprite(config, media, overwrite).executeAsync.runAsyncAndForget
          Behaviors.same

        case DeleteFragment(media, range) =>
          val (start, end) = range
          logger.info(s"Deleting fragment: ${media.id}-$range")

          config.resourcePath.resolve(s"${media.id}-$start-${end}_${media.height}p.mp4").deleteIfExists()

          config.transcode.foreach { transcode =>
            config.resourcePath.resolve(s"${media.id}-${start}_${transcode.scaleHeight}p.webp").deleteIfExists()
            config.resourcePath.resolve(s"${media.id}-$start-${end}_${transcode.scaleHeight}p.mp4").deleteIfExists()
          }

          Behaviors.same
      }
    }
  }
}
