package com.github.merlijn.webapp

import com.typesafe.config.ConfigFactory

object Config {

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
    val path = conf.getString("videos.path")
    val indexPath = conf.getString("videos.index")
    val max = conf.getInt("videos.max")
  }
}
