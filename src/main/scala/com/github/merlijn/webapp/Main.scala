package com.github.merlijn.webapp


object Main extends App with WebServer {

  println("ENV: " + System.getenv().get("ENV"))
}
