package com.github.merlijn.kagera

import com.github.merlijn.kagera.http.WebServerConfig
import com.github.merlijn.kagera.lib.MediaLibConfig
import com.typesafe.config.ConfigFactory
import pureconfig._
import pureconfig.generic.auto._
import scribe.Logging

trait MainConfig extends Logging {

  val env = {
    if (System.getenv().containsKey("ENV"))
      System.getenv().get("ENV")
    else
      "dev"
  }

  val config       = ConfigFactory.load(s"$env/application.conf")
  val configSource = ConfigSource.fromConfig(config)

  val mediaLibConfig  = configSource.at("media").loadOrThrow[MediaLibConfig]
  val webServerConfig = configSource.at("http").loadOrThrow[WebServerConfig]
}
