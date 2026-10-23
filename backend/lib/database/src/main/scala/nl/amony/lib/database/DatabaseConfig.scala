package nl.amony.lib.database

import java.sql.{Connection, DriverManager}

import cats.effect.IO
import pureconfig.ConfigReader

case class DatabaseConfig(host: String, port: Int, database: String, username: String, poolSize: Int, password: String)
    derives ConfigReader {

  def getJdbcConnection: IO[Connection] =
    IO {
      Class.forName("org.postgresql.Driver")
      val jdbcUrl = s"jdbc:postgresql://$host:$port/$database"
      DriverManager.getConnection(jdbcUrl, username, password)
    }
}
