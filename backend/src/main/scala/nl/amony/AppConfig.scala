package nl.amony

import java.nio.file.Path
import java.sql.{Connection, DriverManager}

import cats.effect.IO
import pureconfig.*

import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.resources.ResourceConfig.ResourceBucketConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.modules.search.solr.SolrConfig

case class DatabaseConfig(host: String, port: Int, database: String, username: String, poolSize: Int, password: Option[String]) derives ConfigReader {
  def getJdbcConnection: IO[Connection] =
    IO {
      Class.forName("org.postgresql.Driver")
      val jdbcUrl = s"jdbc:postgresql://$host:$port/$database"
      DriverManager.getConnection(jdbcUrl, username, password.getOrElse(null))
    }
}

case class AppConfig(
  amonyHome: Path,
  resources: ResourceConfig,
  auth: AuthConfig,
  api: WebServerConfig,
  search: SearchConfig,
  solr: SolrConfig,
  database: DatabaseConfig
) derives ConfigReader
