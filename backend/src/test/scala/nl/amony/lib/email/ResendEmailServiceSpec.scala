package nl.amony.lib.email

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.wordspec.AnyWordSpecLike
import sttp.client4.UriContext
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.client4.testing.*
import sttp.model.Method

import nl.amony.lib.email.impl.ResendEmailService

class ResendEmailServiceSpec extends AnyWordSpecLike {

  "ResendEmailService" should {
    "send emails" in {

      val testingBackend = SyncBackendStub
        .whenRequestMatches {
          req =>
            println(req.body)
            req.uri == uri"https://api.resend.com/emails" && req.method == Method.POST
        }
        .thenRespondAdjust("Hello there!")

      val request = Email(
        to      = NonEmptyList.one(EmailAddress.unsafe("merlijn@mvisoftware.dev")),
        subject = "Test Email",
        from    = EmailAddress.unsafe("noreply@amony.me"),
        body    = "Hi there, this is a test email sent via Resend API"
      )

      val expectedJsonBody =
        """
          |{
          |  "from": "noreply@amony.me",
          |  "to": [ "merlijn@mvisoftware.dev" ],
          |  "subject": "Test Email",
          |  "html": "Hi there, this is a test email sent via Resend API"
          |}
          |""".stripMargin

      val apiKey = "test_api_key"

      val service = new ResendEmailService(testingBackend, apiKey)

      val foo = service.send(request)
    }
  }
}
