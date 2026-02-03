package nl.amony.lib.email.impl

import cats.implicits.toFlatMapOps
import cats.{Applicative, FlatMap, Monad}
import io.circe.derivation.Configuration
import io.circe.syntax.*
import io.circe.{Codec, Json, Printer}
import scribe.Logging
import sttp.client4.{Backend, StringBody, UriContext}
import sttp.model.{MediaType, Uri}

import nl.amony.lib.email.*

given Configuration = Configuration.default.withSnakeCaseMemberNames

case class Tag(name: String, value: String) derives Codec

case class Template(
  id: String,
  variables: Option[Map[String, Json]] = None
) derives Codec

case class SendEmailRequest(
  from: String,
  to: Seq[String],
  subject: String,
  bcc: Option[Seq[String]]             = None,
  cc: Option[Seq[String]]              = None,
  scheduledAt: Option[String]          = None,
  replyTo: Option[Seq[String]]         = None,
  html: Option[String]                 = None,
  text: Option[String]                 = None,
  headers: Option[Map[String, String]] = None,
  topicId: Option[String]              = None,
  attachments: Option[Seq[Attachment]] = None,
  tags: Option[Seq[Tag]]               = None,
  template: Option[Template]           = None
) derives Codec

case class Attachment(
  content: Option[String]     = None,
  filename: Option[String]    = None,
  path: Option[String]        = None,
  contentType: Option[String] = None,
  contentId: Option[String]   = None
) derives Codec

case class SendEmailResponse(id: String) derives Codec

class ResendEmailService[F[_]: Monad](httpClient: Backend[F], apiKey: String, url: Uri = uri"""https://api.resend.com""")
    extends EmailService[F], Logging:

  private val jsonPrinter = Printer.spaces2.copy(dropNullValues = true)

  override def send(email: Email): F[Unit] = {

    val emailRequest = SendEmailRequest(
      from    = email.from,
      to      = email.to.toList,
      subject = email.subject,
      bcc     = if email.bcc.nonEmpty then Some(email.bcc) else None,
      cc      = if email.ccc.nonEmpty then Some(email.ccc) else None,
      replyTo = if email.replyTo.nonEmpty then Some(email.replyTo) else None,
      html    = Some(email.body)
    )

    val httpRequest = sttp.client4.basicRequest
      .header("Authorization", s"Bearer $apiKey")
      .body(StringBody(emailRequest.asJson.printWith(jsonPrinter), "utf-8", MediaType.ApplicationJson))
      .post(url.addPath("emails"))

    httpClient.send(httpRequest).flatMap: response =>
      response.body match
        case Left(error) => Monad[F].pure(throw new Exception(s"Failed to send email: $error"))
        case Right(body) => Monad[F].pure(logger.info(s"Email sent: $body"))
  }
