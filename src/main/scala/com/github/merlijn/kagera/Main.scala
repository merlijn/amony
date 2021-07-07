package com.github.merlijn.kagera


object Main extends App with WebServer {

  println("ENV: " + System.getenv().get("ENV"))
}
