lazy val commonSettings = Seq(
  organization := "Dwolla",
  homepage := Option(url("https://github.com/Dwolla/cloudflare-public-hostname-lambda")),
)

lazy val specs2Version = "4.3.0"
lazy val awsSdkVersion = "1.11.475"

lazy val `cloudflare-public-hostname-lambda` = (project in file("."))
  .settings(
    name := "cloudflare-public-hostname-lambda",
    libraryDependencies ++= {
      val fs2AwsVersion = "2.0.0-M16"

      Seq(
        "com.dwolla" %% "scala-cloudformation-custom-resource" % "4.0.0-M3",
        "com.dwolla" %% "fs2-aws" % fs2AwsVersion,
        "io.circe" %% "circe-fs2" % "0.9.0",
        "com.dwolla" %% "cloudflare-api-client" % "4.0.0-M15",
        "org.http4s" %% "http4s-blaze-client" % "0.18.21",
        "com.amazonaws" % "aws-java-sdk-kms" % awsSdkVersion,
        "org.apache.httpcomponents" % "httpclient" % "4.5.2",
        "org.specs2" %% "specs2-core" % specs2Version % Test,
        "org.specs2" %% "specs2-mock" % specs2Version % Test,
        "org.specs2" %% "specs2-matcher-extra" % specs2Version % Test,
        "com.dwolla" %% "testutils-specs2" % "2.0.0-M6" % Test exclude("ch.qos.logback", "logback-classic"),
        "com.dwolla" %% "fs2-aws-testkit" % fs2AwsVersion % Test,
      )
    },
    updateOptions := updateOptions.value.withCachedResolution(false),
  )
  .settings(commonSettings: _*)
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .enablePlugins(UniversalPlugin, JavaAppPackaging)
