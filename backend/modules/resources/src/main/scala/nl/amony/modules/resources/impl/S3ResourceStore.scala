package nl.amony.modules.resources.impl

import java.time.{Duration, Instant}
import java.util.UUID
import scala.jdk.CollectionConverters.*

import cats.data.EitherT
import cats.effect.{IO, Resource}
import cats.implicits.*
import fs2.{Chunk, Stream}
import org.http4s.Uri
import scribe.Logging
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentialsProvider, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.{RequestChecksumCalculation, ResponseChecksumValidation}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import nl.amony.modules.resources.api.*

/** [[ResourceStore]] backed by an S3-compatible object storage bucket. */
final class S3ResourceStore(
  config: S3StorageConfig,
  client: S3Client,
  presigner: S3Presigner
) extends ResourceStore,
      Logging:

  import S3ResourceStore.*

  private val bucketName      = config.bucket
  private val presignDuration = Duration.ofMillis(config.presignedUrlTtl.toMillis)

  override def pathForUpload(resourceId: ResourceId, fileName: String): ResourcePath =
    ResourcePath(s"$resourceId${extractExtension(fileName)}")

  override def pathForReplacement(currentPath: ResourcePath, resourceId: ResourceId): ResourcePath =
    val suffix = UUID.randomUUID().toString.take(8)
    ResourcePath(s"${resourceId}_$suffix${extractExtension(currentPath)}")

  private def withPrefix(key: String): String =
    config.normalizedPrefix.fold(key)(prefix => s"$prefix/$key")

  private[impl] def resolveKey(key: StoreKey): Either[String, String] = key match
    case StoreKey.Resource(path)            => ResourcePath.fromString(path).map(p => withPrefix(s"resources/$p"))
    case StoreKey.Derived(resourceId, name) => Right(withPrefix(s"derived/$resourceId/$name"))

  private def headObject(objectKey: String): IO[Option[HeadObjectResponse]] =
    IO.blocking {
      try Some(client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(objectKey).build()))
      catch
        case _: NoSuchKeyException                   => None
        case e: S3Exception if e.statusCode() == 404 => None
    }

  private def presignGet(objectKey: String, contentType: Option[String]): IO[(java.net.URL, Instant)] =
    IO.blocking {
      val getBuilder = GetObjectRequest.builder().bucket(bucketName).key(objectKey)
      contentType.foreach(getBuilder.responseContentType)
      val request    = GetObjectPresignRequest.builder()
        .signatureDuration(presignDuration)
        .getObjectRequest(getBuilder.build())
        .build()
      val presigned  = presigner.presignGetObject(request)
      (presigned.url(), presigned.expiration())
    }

  override def content(key: StoreKey, contentType: Option[String], eTag: Option[String]): IO[Option[ResourceContent]] =
    resolveKey(key) match
      case Left(message)    =>
        IO(logger.warn(s"Ignoring resource with invalid key '$key': $message")).as(None)
      case Right(objectKey) =>
        headObject(objectKey).flatMap {
          case None    =>
            key match
              case StoreKey.Resource(path) => IO(logger.warn(s"Resource at '$path' does not exist in S3 at '$objectKey'")).as(None)
              case _                       => IO.pure(None)
          case Some(_) =>
            presignGet(objectKey, contentType).map { case (url, expiresAt) =>
              Some(ResourceContent.Redirect(Uri.unsafeFromString(url.toString), expiresAt))
            }
        }

  override def exists(key: StoreKey): IO[Boolean] =
    resolveKey(key) match
      case Left(_)          => IO.pure(false)
      case Right(objectKey) => headObject(objectKey).map(_.isDefined)

  private def putSingleObject(objectKey: String, contentType: Option[String], bytes: Array[Byte]): IO[Unit] =
    IO.blocking {
      val builder = PutObjectRequest.builder().bucket(bucketName).key(objectKey).contentLength(bytes.length.toLong)
      contentType.foreach(builder.contentType)
      client.putObject(builder.build(), RequestBody.fromBytes(bytes))
    }.void

  private def abortMultipart(objectKey: String, uploadId: String): IO[Unit] =
    IO.blocking {
      client.abortMultipartUpload(
        AbortMultipartUploadRequest.builder().bucket(bucketName).key(objectKey).uploadId(uploadId).build()
      )
    }.void.handleErrorWith(e => IO(logger.warn(s"Failed to abort multipart upload '$uploadId' for '$objectKey'", e)))

  private def putMultipart(objectKey: String, contentType: Option[String], parts: Stream[IO, Chunk[Byte]]): IO[Unit] =
    IO.blocking {
      val builder = CreateMultipartUploadRequest.builder().bucket(bucketName).key(objectKey)
      contentType.foreach(builder.contentType)
      client.createMultipartUpload(builder.build()).uploadId()
    }.flatMap { uploadId =>
      val upload =
        parts.zipWithIndex
          .evalMap { case (chunk, idx) =>
            val partNumber = (idx + 1).toInt
            val bytes      = chunk.toArray
            IO.blocking {
              val response = client.uploadPart(
                UploadPartRequest.builder()
                  .bucket(bucketName)
                  .key(objectKey)
                  .uploadId(uploadId)
                  .partNumber(partNumber)
                  .contentLength(bytes.length.toLong)
                  .build(),
                RequestBody.fromBytes(bytes)
              )
              CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build()
            }
          }
          .compile.toList
          .flatMap { completed =>
            IO.blocking {
              client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                  .bucket(bucketName)
                  .key(objectKey)
                  .uploadId(uploadId)
                  .multipartUpload(CompletedMultipartUpload.builder().parts(completed.asJava).build())
                  .build()
              )
            }.void
          }

      upload
        .onError(_ => abortMultipart(objectKey, uploadId))
        .onCancel(abortMultipart(objectKey, uploadId))
    }

  override def put(key: StoreKey, contentType: Option[String], source: Stream[IO, Byte]): EitherT[IO, String, Unit] =
    EitherT.fromEither[IO](resolveKey(key)).flatMap { objectKey =>
      val chunked = source.chunkN(MultipartPartSize, allowFewer = true)
      val write   = chunked.pull.uncons1.flatMap {
        case None                => fs2.Pull.eval(putSingleObject(objectKey, contentType, Array.emptyByteArray))
        case Some((first, rest)) =>
          rest.pull.uncons1.flatMap {
            case None                 => fs2.Pull.eval(putSingleObject(objectKey, contentType, first.toArray))
            case Some((second, tail)) =>
              val allParts = Stream.emit(first) ++ Stream.emit(second) ++ tail
              fs2.Pull.eval(putMultipart(objectKey, contentType, allParts))
          }
      }.stream.compile.drain

      EitherT(write.attempt.map(_.left.map(error => s"Failed to store '$objectKey': ${error.getMessage}")))
    }

  override def putBytes(key: StoreKey, contentType: Option[String], bytes: Array[Byte]): IO[Unit] =
    resolveKey(key) match
      case Left(message)    => IO.raiseError(new IllegalArgumentException(message))
      case Right(objectKey) => putSingleObject(objectKey, contentType, bytes)

  override def delete(key: StoreKey): IO[Unit] =
    resolveKey(key) match
      case Left(message)    => IO.raiseError(new IllegalArgumentException(message))
      case Right(objectKey) =>
        IO.blocking {
          client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build())
        }.void

  override def openSource(path: ResourcePath): IO[Option[MediaSource]] =
    resolveKey(StoreKey.Resource(path)) match
      case Left(_)          => IO.pure(None)
      case Right(objectKey) =>
        headObject(objectKey).flatMap {
          case None    => IO.pure(None)
          case Some(_) => presignGet(objectKey, contentType = None).map { case (url, _) => Some(MediaSource.RemoteUrl(url.toURI)) }
        }

  override def stat(path: ResourcePath): IO[Option[StoredResource]] =
    resolveKey(StoreKey.Resource(path)) match
      case Left(_)          => IO.pure(None)
      case Right(objectKey) =>
        headObject(objectKey).map(_.map { head =>
          StoredResource(
            path             = path,
            size             = head.contentLength(),
            timeLastModified = head.lastModified().toEpochMilli,
            fileKey          = cleanETag(head.eTag())
          )
        })

  private def deleteBatch(keys: List[String]): IO[Unit] =
    keys.grouped(MaxDeleteBatchSize).toList.traverse_ { batch =>
      IO.blocking {
        val ids = batch.map(k => ObjectIdentifier.builder().key(k).build()).asJava
        client.deleteObjects(
          DeleteObjectsRequest.builder()
            .bucket(bucketName)
            .delete(Delete.builder().objects(ids).quiet(true).build())
            .build()
        )
      }.void
    }

  private def listAndDelete(prefix: String): IO[Unit] =
    def loop(continuationToken: Option[String]): IO[Unit] =
      IO.blocking {
        val builder = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix)
        continuationToken.foreach(builder.continuationToken)
        client.listObjectsV2(builder.build())
      }.flatMap { response =>
        val toDelete = response.contents().asScala.map(_.key()).toList
        deleteBatch(toDelete) >>
          (if response.isTruncated then loop(Option(response.nextContinuationToken())) else IO.unit)
      }
    loop(None)

  override def deleteDerived(resourceId: ResourceId): IO[Unit] =
    listAndDelete(withPrefix(s"derived/$resourceId/"))
      .handleErrorWith(e => IO(logger.warn(s"Failed to delete derived objects for $resourceId", e)))

object S3ResourceStore:

  private[impl] val MultipartPartSize: Int = 8 * 1024 * 1024
  private val MaxDeleteBatchSize: Int      = 1000

  private def cleanETag(raw: String): Option[String] =
    Option(raw).map(_.stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty)

  private def extractExtension(fileName: String): String =
    val dotIndex = fileName.lastIndexOf('.')
    if dotIndex > 0 && dotIndex < fileName.length - 1 then
      val ext = fileName.substring(dotIndex)
      if ext.length <= 16 && !ext.contains('/') && !ext.contains('\\') then ext else ""
    else ""

  private def resolveCredentials(config: S3StorageConfig, overrideProvider: Option[AwsCredentialsProvider]): AwsCredentialsProvider =
    overrideProvider.getOrElse {
      (config.accessKeyId, config.secretAccessKey) match
        case (Some(keyId), Some(secret)) => StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret))
        case _                           => DefaultCredentialsProvider.builder().build()
    }

  def resource(
    config: S3StorageConfig,
    credentialsProvider: Option[AwsCredentialsProvider] = None
  ): Resource[IO, S3ResourceStore] =
    val credentials = resolveCredentials(config, credentialsProvider)
    val s3Config    = S3Configuration.builder()
      .pathStyleAccessEnabled(config.pathStyleAccess)
      .chunkedEncodingEnabled(false)
      .build()
    val region      = Region.of(config.region)

    val clientResource = Resource.fromAutoCloseable(IO.blocking {
      val builder = S3Client.builder()
        .region(region)
        .credentialsProvider(credentials)
        .serviceConfiguration(s3Config)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
      config.endpoint.foreach(builder.endpointOverride)
      builder.build()
    })

    val presignerResource = Resource.fromAutoCloseable(IO.blocking {
      val builder = S3Presigner.builder()
        .region(region)
        .credentialsProvider(credentials)
        .serviceConfiguration(s3Config)
      config.endpoint.foreach(builder.endpointOverride)
      builder.build()
    })

    for
      client    <- clientResource
      presigner <- presignerResource
    yield new S3ResourceStore(config, client, presigner)
