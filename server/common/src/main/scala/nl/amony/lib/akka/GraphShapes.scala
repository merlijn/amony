package nl.amony.lib.akka

import akka.NotUsed
import akka.stream.ClosedShape
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}

object GraphShapes {
  def broadcast[T, A, B](s: Source[T, NotUsed], a: Sink[T, A], b: Sink[T, B]): RunnableGraph[(A, B)] =
    RunnableGraph.fromGraph(GraphDSL.createGraph(a, b)((_, _)) { implicit builder =>
      (a, b) =>
        import GraphDSL.Implicits._
        val broadcast = builder.add(Broadcast[T](2))
        s ~> broadcast.in
        broadcast ~> a.in
        broadcast ~> b.in
        ClosedShape
    })
}
