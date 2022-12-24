package nl.amony.lib.config

import com.typesafe.config.Config
import pureconfig.{ConfigReader, ConfigSource}

import scala.reflect.ClassTag

object ConfigHelper {
  def loadConfig[T: ClassTag](config: Config, path: String)(implicit reader: ConfigReader[T]): T = {

    val configSource = ConfigSource.fromConfig(config.getConfig(path))
    val configObj = configSource.loadOrThrow[T]

    configObj
  }
}
