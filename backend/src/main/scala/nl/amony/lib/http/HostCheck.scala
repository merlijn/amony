package nl.amony.lib.http

/**
 * Host allow-list helpers.
 *
 * The backend derives its public origin from the request (X-Forwarded-Host, else Host) to build
 * OAuth redirect URIs. So a client must not be able to make it use an unexpected domain: every
 * request's host is checked against a configured allow-list before it is handled.
 */
object HostCheck:

  /** The host the request was made with: X-Forwarded-Host (set by the reverse proxy) wins over Host. */
  def effectiveHost(xForwardedHost: Option[String], host: Option[String]): String =
    def first(value: Option[String]) = value.map(_.split(",").head.trim).filter(_.nonEmpty)
    first(xForwardedHost).orElse(first(host)).getOrElse("")

  /** Whether `host` is in `allowedHosts` (case-insensitive). */
  def isAllowed(allowedHosts: List[String], host: String): Boolean =
    val candidate = host.trim
    candidate.nonEmpty && allowedHosts.exists(_.trim.equalsIgnoreCase(candidate))
