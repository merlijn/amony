package nl.amony.lib.otel

import java.io.{PrintWriter, StringWriter}
import scala.concurrent.duration.*
import scala.util.chaining.*

import cats.Monad
import cats.mtl.Local
import cats.syntax.all.*
import org.typelevel.otel4s.logs.Logger as OtelLogger
import org.typelevel.otel4s.logs.{LogRecordBuilder, LoggerProvider, Severity}
import org.typelevel.otel4s.semconv.attributes.{CodeAttributes, ExceptionAttributes}
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}
import scribe.*

class ScribeLoggerSupport[F[_]: Monad, Ctx](
  provider: LoggerProvider[F, Ctx],
  local: Local[F, Ctx]
) extends LoggerSupport[F[Unit]] {

  override def log(record: => LogRecord): F[Unit] =
    for
      r      <- Monad[F].pure(record)
      // use the library version here
      logger <- provider.logger(r.className).withVersion("0.0.1").get

      // retrieve the current context
      ctx <- local.ask

      // Check if logging instrumentation is enabled for the current context.
      // NOTE: this does not check whether an individual logger is enabled.
      // If the OpenTelemetry logging pipeline (backed by OTLP) is active,
      // `isEnabled` will always return true regardless of the specific logger
      isEnabled <- logger.meta.isEnabled(ctx, toSeverity(r.level), None)

      // if enabled, build and emit the log record
      _ <- if isEnabled then buildLogRecord(logger, r).emit else Monad[F].unit
    yield ()

  private def buildLogRecord(
    logger: OtelLogger[F, Ctx],
    record: LogRecord
  ): LogRecordBuilder[F, Ctx] =
    logger.logRecordBuilder
      .pipe(l => toSeverity(record.level).fold(l)(l.withSeverity))
      .withSeverityText(record.level.name)
      .withTimestamp(record.timeStamp.millis)
      .withBody(AnyValue.string(record.logOutput.plainText))
      .pipe { builder =>
        builder.addAttributes(
          if record.thread.getId != -1 then {
            Attributes(
              Attribute("thread.id", record.thread.getId),
              Attribute("thread.name", record.thread.getName)
            )
          } else {
            Attributes(
              Attribute("thread.name", record.thread.getName)
            )
          }
        )
      }
      // code path info
      .pipe { builder =>
        builder.addAttributes(codePathAttributes(record))
      }
      // exception info
      .pipe { builder =>
        record.messages
          .collect {
            case scribe.throwable.TraceLoggableMessage(throwable) => throwable
          }
          .foldLeft(builder)((b, t) => b.addAttributes(exceptionAttributes(t)))
      }
      // context
      // MDC
      .pipe { builder =>
        if record.data.nonEmpty then builder.addAttributes(dataAttributes(record.data))
        else builder
      }

  private def toSeverity(level: Level): Option[Severity] =
    level match {
      case Level("TRACE", _) => Some(Severity.trace)
      case Level("DEBUG", _) => Some(Severity.debug)
      case Level("INFO", _)  => Some(Severity.info)
      case Level("WARN", _)  => Some(Severity.warn)
      case Level("ERROR", _) => Some(Severity.error)
      case Level("FATAL", _) => Some(Severity.fatal)
      case _                 => None
    }

  private def codePathAttributes(record: LogRecord): Attributes = {
    val builder = Attributes.newBuilder

    builder += Attribute("code.namespace", record.className)
    builder += CodeAttributes.CodeFilePath(record.fileName)
    builder ++= record.line.map(line => CodeAttributes.CodeLineNumber(line.toLong))
    builder ++= record.column.map(col => CodeAttributes.CodeColumnNumber(col.toLong))
    builder ++= record.methodName.map(name => CodeAttributes.CodeFunctionName(name))

    builder.result()
  }

  private def exceptionAttributes(exception: Throwable): Attributes = {
    val builder = Attributes.newBuilder

    builder += ExceptionAttributes.ExceptionType(exception.getClass.getName)

    val message = exception.getMessage
    if message != null then {
      builder += ExceptionAttributes.ExceptionMessage(message)

    }

    if exception.getStackTrace.nonEmpty then {
      val stringWriter = new StringWriter()
      val printWriter  = new PrintWriter(stringWriter)

      exception.printStackTrace(printWriter)
      builder += ExceptionAttributes.ExceptionStacktrace(stringWriter.toString)
    }

    builder.result()
  }

  private def dataAttributes(data: Map[String, () => Any]): Attributes = {
    val builder = Attributes.newBuilder
    data.foreach { case (key, getValue) =>
      getValue().asInstanceOf[Matchable] match {
        case v: String  => builder += Attribute(key, v)
        case v: Boolean => builder += Attribute(key, v)
        case v: Byte    => builder += Attribute(key, v.toLong)
        case v: Short   => builder += Attribute(key, v.toLong)
        case v: Int     => builder += Attribute(key, v.toLong)
        case v: Long    => builder += Attribute(key, v)
        case v: Double  => builder += Attribute(key, v)
        case v: Float   => builder += Attribute(key, v.toDouble)
        case _          =>
        // ignore the rest.
        // alternatively, you can stringify the value:
        // builder += Attribute(key, v.toString)
      }
    }
    builder.result()
  }
}
