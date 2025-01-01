addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "1.0.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("de.gccc.sbt" % "sbt-jib" % "1.3.7")
addSbtPlugin("com.github.cb372" % "sbt-explicit-dependencies" % "0.3.1")
addSbtPlugin("com.disneystreaming.smithy4s"  % "smithy4s-sbt-codegen" % "0.18.27")
libraryDependencies += "com.google.cloud.tools" % "jib-core" % "0.27.2"
