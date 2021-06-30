package com.github.merlijn.webapp


object Main extends App with WebServer {

  println("ENV: " + System.getenv().get("ENV"))

//  val dir = File("/Users/merlijn/Documents")
//  dir.listRecursively.filterNot { f =>
//    f.name.startsWith(".") || !f.isRegularFile || f.size() == 0
//  }.foreach { f =>
//    println(s"${f.name}: ${FFMpeg.fakeHash(f)}")
//  }
}
