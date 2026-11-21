package nl.amony.modules.resources.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ETag:
  def of(parts: String*): String =
    Base32.encode(MessageDigest.getInstance("SHA-1").digest(parts.mkString("\u0000").getBytes(StandardCharsets.UTF_8)))

