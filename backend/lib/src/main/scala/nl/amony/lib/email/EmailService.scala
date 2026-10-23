package nl.amony.lib.email

import cats.data.NonEmptyList

case class Email(
  to: NonEmptyList[EmailAddress],
  from: EmailAddress,
  subject: String,
  body: String,
  ccc: Seq[EmailAddress]     = Nil,
  bcc: Seq[EmailAddress]     = Nil,
  replyTo: Seq[EmailAddress] = Nil
)

trait EmailService[F[_]]:
  def send(email: Email): F[Unit]
