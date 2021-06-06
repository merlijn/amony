package com.github.merlijn.webapp

class Hash {

  val w = 32
  val h = 3

  val pixelSize = 5

  val pre =
    s"""
       |<?xml version="1.0" encoding="UTF-8" standalone="no"?>
       |<svg xmlns="http://www.w3.org/2000/svg" width="${w*pixelSize+2}" height="${h*pixelSize+2}">
       |""".stripMargin

  val post = "</svg>"

  val colors = List("cyan", "magenta", "yellow", "black", "blue", "red", "green", "white")

  def color() = colors(scala.util.Random.nextInt(colors.size))

  println(pre)

  for (x <- 1 to w)
    for (y <- 1 to h)
      println(s"""<rect x="${(x-1) * pixelSize}" y="${(y-1) * pixelSize}" width="$pixelSize" height="$pixelSize" style="fill:${color()}" />""")

  println(post)
}
