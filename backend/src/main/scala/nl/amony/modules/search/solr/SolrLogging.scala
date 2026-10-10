package nl.amony.modules.search.solr

import cats.effect.IO
import scribe.Logging

/** Shared error handling for Solr operations: logs the failure and re-raises it. */
private[solr] trait SolrLogging extends Logging:

  protected def loggingFailureIO[T](f: => T): IO[T] =
    IO.blocking(f).handleErrorWith {
      case e: Exception => IO(logger.error("Error while executing solr operation", e)) >> IO.raiseError(e)
    }
