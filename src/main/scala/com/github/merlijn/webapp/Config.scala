package com.github.merlijn.webapp

import com.typesafe.config.ConfigFactory
import scribe.Logging

import java.nio.file.Path

object Config extends Logging {

  val env = {
    if (System.getenv().containsKey("ENV"))
      System.getenv().get("ENV")
    else
      "dev"
  }

  val conf = ConfigFactory.load(s"$env/application.conf")

  object http {

    val port = conf.getInt("http.port")
    val hostname = conf.getString("http.hostName")
    val hostClient = conf.getBoolean("http.client-host")
    val clientPath = conf.getString("http.client-files")
  }

  object library {
    val path = Path.of(conf.getString("videos.path"))
    val indexPath = Path.of(conf.getString("videos.index"))
    val max = conf.getInt("videos.max")
  }
}
