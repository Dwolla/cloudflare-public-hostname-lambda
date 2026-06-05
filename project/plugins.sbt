addSbtPlugin("org.typelevel" % "sbt-typelevel-github-actions" % "0.8.2")
addSbtPlugin("org.typelevel" % "sbt-typelevel-settings" % "0.8.2")
addSbtPlugin("org.typelevel" % "sbt-typelevel-mergify" % "0.8.2")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.21.0")
addSbtPlugin("org.typelevel" % "sbt-feral-lambda" % "0.3.1")
addSbtPlugin("com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.18.42")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.12.0")

libraryDependencies ++= Seq(
  "software.amazon.awscdk" % "aws-cdk-lib" % "2.220.0",
)
