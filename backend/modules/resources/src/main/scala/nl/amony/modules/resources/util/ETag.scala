package nl.amony.modules.resources.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import nl.amony.modules.resources.api.ResourceInfo

object ETag:

  def of(parts: String*): String =
    Base32.encode(MessageDigest.getInstance("SHA-1").digest(parts.mkString("\u0000").getBytes(StandardCharsets.UTF_8)))

  /** Validator for a resource's original content, derived from its partial hash and last modified time. */
  def forResource(info: ResourceInfo): Option[String] =
    info.partialHash.map(sourceHash => of(sourceHash, info.timeLastModified.getOrElse(0L).toString))
