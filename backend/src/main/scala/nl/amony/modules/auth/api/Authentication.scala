package nl.amony.modules.auth.api

/** The upstream identity provider's ID token, kept so it can be sent as `id_token_hint` at logout. */
case class ProviderIdToken(provider: String, idToken: String):
  def encode: String = s"$provider|$idToken"

object ProviderIdToken:
  /** Parses the `provider|idToken` value stored in the provider_id_token cookie. */
  def decode(value: String): Option[ProviderIdToken] =
    value.split("\\|", 2) match
      case Array(provider, idToken) if provider.nonEmpty && idToken.nonEmpty => Some(ProviderIdToken(provider, idToken))
      case _                                                                => None

case class Authentication(accessToken: String, refreshToken: String, providerIdToken: Option[ProviderIdToken] = None)
