package nl.amony.lib.observability

import cats.Monad
import cats.effect.kernel.{Async, Sync}
import cats.mtl.Local
import org.typelevel.otel4s.logs.LoggerProvider
import scribe.LogRecord
import scribe.output.LogOutput
import scribe.output.format.OutputFormat
import scribe.writer.Writer

class ScribeOtel4sWriter[F[_]: {Monad, Async, Sync}, Ctx](
  provider: LoggerProvider[F, Ctx],
  local: Local[F, Ctx],
  dispatcher: cats.effect.std.Dispatcher[F]
) extends ScribeLoggerSupport[F, Ctx](provider, local) with Writer:

  override def write(record: LogRecord, output: LogOutput, outputFormat: OutputFormat): Unit =
    dispatcher.unsafeRunAndForget(log(record))
    ()
