package nl.amony.lib.email

import org.scalatest.wordspec.AnyWordSpecLike

class EmailAddressSpec extends AnyWordSpecLike {

  "EmailAddress" should {
    "validate correct email addresses" in {
      val validEmails =
        List(
          "test@gmail.com"
        )

      validEmails.foreach {
        email =>
          assert(EmailAddress.isValidEmail(email), s"expected $email to be a valid email address")
      }
    }
  }
}
