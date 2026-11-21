package nl.amony.modules.resources.impl

import java.nio.file.Path

import cats.effect.IO

import nl.amony.modules.resources.api.*

/** Operations on the media of a resource, independent of how the bucket stores it. */
trait MediaOperations:

  def contentMetaForPath(path: Path): IO[Either[ContentUnreadable, ContentMeta]]

  def addFastStart(video: Path, container: VideoContainer, outputFile: Option[Path] = None): IO[Path]

  def remuxForStreaming(info: ResourceInfo): IO[Either[StreamabilityError, String]]

  def commitStreamability(info: ResourceInfo, tempFile: Option[String]): IO[Either[StreamabilityError, ResourceInfo]]

  def originalETag(info: ResourceInfo): Option[String]

  def derivedResource(info: ResourceInfo, operation: ResourceOperation): IO[Option[StreamedResource]]

  def previewOperations(info: ResourceInfo): List[ResourceOperation]

  def runPreviewOperation(info: ResourceInfo, operation: ResourceOperation): IO[Unit]

  def generatePreviews(info: ResourceInfo): IO[Unit]

final case class ContentUnreadable(path: Path, cause: Throwable):
  def message: String = s"Failed to read content of $path: ${Option(cause.getMessage).getOrElse(cause.getClass.getName)}"
