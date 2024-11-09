package nl.amony.webserver

import cats.effect.{IO, IOApp}
import nl.amony.search.solr.TarGzExtractor

import java.nio.file.Path

object Test extends IOApp.Simple {

  def run: IO[Unit] = {

    IO {
      TarGzExtractor.extractResourceTarGz("/solr.tar.gz", Path.of("/Users/merlijn/test"))
    }
  }
}
