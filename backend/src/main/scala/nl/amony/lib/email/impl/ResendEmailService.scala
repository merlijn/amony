package nl.amony.lib.email.impl

import cats.effect.IO
import io.circe.derivation.Configuration
import io.circe.syntax.*
import io.circe.{Codec, Json, Printer}
import sttp.client4.{Backend, UriContext}
import sttp.model.Uri

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

class ResendEmailService(apiKey: String, httpClient: Backend[IO], url: Uri = uri"""https://api.resend.com""") extends EmailService:

  private val jsonPrinter = Printer.spaces2.copy(dropNullValues = true)

  override def send(email: Email): IO[Unit] = {

    val emailRequest = SendEmailRequest(
      from    = email.from,
      to      = email.to.toList,
      subject = email.subject,
      bcc     = if email.bcc.nonEmpty then Some(email.bcc) else None,
      cc      = if email.ccc.nonEmpty then Some(email.ccc) else None,
      replyTo = if email.replyTo.nonEmpty then Some(email.replyTo) else None,
      html    = Some(email.body)
    )

    sttp.client4.basicRequest
      .header("Authorization", s"Bearer $apiKey")
      .body(emailRequest.asJson.printWith(jsonPrinter))
      .post(url.addPath("emails"))
      .send(httpClient).flatMap: response =>
        response.body match
          case Left(error)  => IO.raiseError(new Exception(s"Failed to send email: $error"))
          case Right(value) => IO.pure(println(s"Email sent successfully, body: $value"))
  }
