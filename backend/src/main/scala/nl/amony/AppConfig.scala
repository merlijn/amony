package nl.amony

import java.sql.{Connection, DriverManager}

import cats.effect.IO
import pureconfig.*

import nl.amony.lib.observability.ObservabilityConfig
import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.modules.search.solr.SolrConfig

case class DatabaseConfig(host: String, port: Int, database: String, username: String, poolSize: Int, password: String) derives ConfigReader {
  def getJdbcConnection: IO[Connection] =
    IO {
      Class.forName("org.postgresql.Driver")
      val jdbcUrl = s"jdbc:postgresql://$host:$port/$database"
      DriverManager.getConnection(jdbcUrl, username, password)
    }
}

case class AppConfig(
  resources: ResourceConfig,
  auth: AuthConfig,
  api: WebServerConfig,
  search: SearchConfig,
  database: DatabaseConfig,
  observability: ObservabilityConfig
) derives ConfigReader
