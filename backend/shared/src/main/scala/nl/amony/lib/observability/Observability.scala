package nl.amony.lib.observability

import cats.Monad
import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import org.typelevel.otel4s.logs.LoggerProvider
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context, LocalContextProvider}
import org.typelevel.otel4s.trace.TracerProvider
import scribe.Logger
import scribe.format.Formatter
import scribe.handler.{AsynchronousLogHandle, LogHandler, Overflow}
import scribe.modify.LogModifier

object Observability {
  def resource[F[_]: {Monad, Async,
    LocalContextProvider}](config: ObservabilityConfig): Resource[F, (MeterProvider[F], TracerProvider[F], LoggerProvider[F, Context])] = {

    def otelObservability(): Resource[F, (MeterProvider[F], TracerProvider[F], LoggerProvider[F, Context])] =
      for
        dispatcher <- Dispatcher.sequential[F]
        otel       <- OtelJava.autoConfigured[F]()
      yield {

        val consoleHandler = LogHandler(
          formatter = Formatter.classic
        )

        import scribe.Level
        import scribe.filter.*

        def packageModifier(pkg: String, level: String): LogModifier =
          select(packageName.startsWith(pkg)).setLevel(Level.get(level).getOrElse(Level.Info))

        val modifiers = config.logLevels.foldLeft(List.empty[LogModifier]) {
          case (modifiers, (pkg, level)) => packageModifier(pkg, level) :: modifiers
        }

        val otelHandler = LogHandler(
          writer = new ScribeOtel4sWriter[F, Context](otel.loggerProvider, otel.localContext, dispatcher),
          handle = AsynchronousLogHandle(
            maxBuffer = 1024,
            overflow  = Overflow.DropOld
          )
        ).withModifiers(modifiers*)

        Logger.root.clearHandlers()
          .withHandler(consoleHandler)
          .withHandler(otelHandler)
          .replace()

        (otel.meterProvider, otel.tracerProvider, otel.loggerProvider)
      }

    def nooopObservability(): Resource[F, (MeterProvider[F], TracerProvider[F], LoggerProvider[F, Context])] =
      Resource.pure(MeterProvider.noop[F], TracerProvider.noop[F], LoggerProvider.noop[F, Context])

    if config.otelEnabled then otelObservability() else nooopObservability()
  }
}
