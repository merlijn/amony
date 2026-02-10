package nl.amony

import cats.effect.IO
import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.modules.search.solr.SolrConfig
import pureconfig.*

import java.nio.file.Path
import java.sql.{Connection, DriverManager}

case class DatabaseConfig(host: String, port: Int, database: String, username: String, poolSize: Int, password: String) derives ConfigReader {
  def getJdbcConnection: IO[Connection] =
    IO {
      Class.forName("org.postgresql.Driver")
      val jdbcUrl = s"jdbc:postgresql://$host:$port/$database"
      DriverManager.getConnection(jdbcUrl, username, password)
    }
}

case class ObservabilityConfig(otelEnabled: Boolean) derives ConfigReader

case class AppConfig(
  amonyHome: Path,
  resources: ResourceConfig,
  auth: AuthConfig,
  api: WebServerConfig,
  search: SearchConfig,
  database: DatabaseConfig,
  observability: ObservabilityConfig
) derives ConfigReader
