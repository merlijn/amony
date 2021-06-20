package com.github.merlijn.webapp

import com.typesafe.config.ConfigFactory

object Config {
  val conf = ConfigFactory.load

  val port = conf.getInt("webapp.http.port")
  val hostname = conf.getString("webapp.http.hostName")

  val path = conf.getString("videos.path")
  val indexPath = conf.getString("videos.index")
}
