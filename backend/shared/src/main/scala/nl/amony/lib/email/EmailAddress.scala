package nl.amony.lib.email

opaque type EmailAddress <: String = String

object EmailAddress:

  private val emailRegex =
    """^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  private[email] def isValidEmail(email: String): Boolean =
    emailRegex.matches(email)

  def unsafe(address: String): EmailAddress = address

  def apply(address: String): Option[EmailAddress] =
    Some(address).filter(isValidEmail)
