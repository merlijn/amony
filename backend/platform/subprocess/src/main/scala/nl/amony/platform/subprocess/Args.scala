package nl.amony.platform.subprocess

sealed trait Arg {
  def toArgs: List[String]
}

object Arg {
  private case class Single(value: String) extends Arg {
    def toArgs: List[String] = List(value)
  }

  private case class Pair(flag: String, value: String) extends Arg {
    def toArgs: List[String] = List(flag, value)
  }

  private case class Group(values: Seq[String]) extends Arg {
    def toArgs: List[String] = values.toList
  }

  private case object Empty extends Arg {
    def toArgs: List[String] = Nil
  }

  given stringToArg: Conversion[String, Arg] = Single(_)

  given pairToArg: Conversion[(String, String), Arg] = Pair(_, _)

  given seqToArg: Conversion[Seq[String], Arg] = Group(_)

  given optStringToArg: Conversion[Option[String], Arg] = {
    case Some(value) => Single(value)
    case None        => Empty
  }

  given optPairToArg: Conversion[Option[(String, String)], Arg] = {
    case Some((flag, value)) => Pair(flag, value)
    case None                => Empty
  }

  given optSeqToArg: Conversion[Option[Seq[String]], Arg] = {
    case Some(values) => Group(values)
    case None         => Empty
  }
}
