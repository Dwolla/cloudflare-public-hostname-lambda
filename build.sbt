import org.typelevel.sbt.gha.WorkflowStep

evictionErrorLevel := Level.Warn

ThisBuild / organization := "Dwolla"
ThisBuild / homepage := Option(url("https://github.com/Dwolla/cloudflare-public-hostname-lambda"))
ThisBuild / licenses += ("MIT", url("http://opensource.org/licenses/MIT"))
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+github@dwolla.com",
    url("https://dwolla.com")
  ),
)
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots
ThisBuild / mergifyStewardConfig ~= { _.map {
  _.withAuthor("dwolla-oss-scala-steward[bot]")
    .withMergeMinors(true)
}}
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
ThisBuild / githubWorkflowBuild += WorkflowStep.Sbt(List("npmPackage"), name = Some("Package"))

lazy val `cloudflare-public-hostname-lambda` = project
  .in(file("."))
  .settings(
    name := "cloudflare-public-hostname-lambda",
    smithy4sAwsSpecs ++= Seq(AWS.kms),
    scalacOptions += "-Wconf:src=src_managed/.*:s",
    dependencyOverrides += "org.scala-lang" %% "scala3-library" % scalaVersion.value,
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %%% "feral-lambda-cloudformation-custom-resource" % "0.3.1",
        "org.typelevel" %%% "cats-tagless-core" % "0.16.3",
        "com.dwolla" %%% "cloudflare-api-client" % "4.0-e2f7bfc-SNAPSHOT",
        "com.dwolla" %%% "natchez-tagless" % "0.2.6",
        "com.disneystreaming.smithy4s" %%% "smithy4s-cats" % smithy4sVersion.value,
        "com.disneystreaming.smithy4s" %%% "smithy4s-http4s" % smithy4sVersion.value,
        "com.disneystreaming.smithy4s" %%% "smithy4s-aws-http4s" % smithy4sVersion.value,
        "com.disneystreaming.smithy4s" %%% "smithy4s-json" % smithy4sVersion.value,
        "org.http4s" %%% "http4s-ember-client" % "0.23.30",
        "org.typelevel" %%% "mouse" % "1.3.2",
        "org.tpolecat" %%% "natchez-mtl" % "0.3.8",
        "org.tpolecat" %%% "natchez-xray" % "0.3.8",
        "org.tpolecat" %%% "natchez-http4s" % "0.6.1",
        "org.tpolecat" %%% "natchez-http4s-mtl" % "0.6.1",
        "org.typelevel" %%% "log4cats-core" % "2.7.1",
        "org.typelevel" %%% "log4cats-js-console" % "2.7.1",
        "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-circe" % "2.38.0",
        "org.typelevel" %%% "munit-cats-effect" % "2.1.0" % Test,
        "org.scalameta" %%% "munit" % "1.2.0" % Test,
        "org.scalameta" %%% "munit-scalacheck" % "1.2.0" % Test,
        "org.typelevel" %%% "scalacheck-effect-munit" % "2.1.0-RC1" % Test,
        "org.tpolecat" %%% "natchez-testkit" % "0.3.8" % Test,
        "org.typelevel" %%% "log4cats-testing" % "2.7.1" % Test,
      )
    },
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
    ),
    buildInfoPackage := "com.dwolla.lambda.cloudflare.record",

  )
  .enablePlugins(BuildInfoPlugin, CdkDeployPlugin, LambdaJSPlugin, Smithy4sCodegenPlugin)
