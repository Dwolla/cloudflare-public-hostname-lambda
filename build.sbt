evictionErrorLevel := Level.Warn

ThisBuild / organization := "Dwolla"
ThisBuild / homepage := Option(url("https://github.com/Dwolla/cloudflare-public-hostname-lambda"))
ThisBuild / scalaVersion := "2.13.16"

lazy val `cloudflare-public-hostname-lambda` = project
  .in(file("."))
  .settings(
    name := "cloudflare-public-hostname-lambda",
    smithy4sAwsSpecs ++= Seq(AWS.kms),
    scalacOptions += "-Wconf:src=src_managed/.*:s",
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %%% "feral-lambda-cloudformation-custom-resource" % "0.3.1",
        "com.dwolla" %%% "cloudflare-api-client" % "4.0.0-M16",
        "com.disneystreaming.smithy4s" %%% "smithy4s-http4s" % smithy4sVersion.value,
        "com.disneystreaming.smithy4s" %%% "smithy4s-aws-http4s" % smithy4sVersion.value,
        "com.disneystreaming.smithy4s" %%% "smithy4s-json" % smithy4sVersion.value,
        "org.http4s" %%% "http4s-ember-client" % "0.23.30",
        "org.typelevel" %%% "mouse" % "1.3.2",
        "org.tpolecat" %%% "natchez-mtl" % "0.3.8",
        "org.tpolecat" %%% "natchez-xray" % "0.3.8",
        "org.tpolecat" %%% "natchez-http4s" % "0.6.1",
        "org.typelevel" %%% "log4cats-core" % "2.7.1",
        "org.typelevel" %%% "log4cats-js-console" % "2.7.1",
        "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-circe" % "2.38.0",
      )
    },
    updateOptions := updateOptions.value.withCachedResolution(false),
  )
  .enablePlugins(Smithy4sCodegenPlugin, LambdaJSPlugin)
