package nl.amony.lib.email

import cats.data.NonEmptyList
import cats.effect.IO

case class Email(
  to: NonEmptyList[EmailAddress],
  from: EmailAddress,
  subject: String,
  body: String,
  ccc: Seq[EmailAddress]     = Nil,
  bcc: Seq[EmailAddress]     = Nil,
  replyTo: Seq[EmailAddress] = Nil
)

trait EmailService:
  def send(email: Email): IO[Unit]
