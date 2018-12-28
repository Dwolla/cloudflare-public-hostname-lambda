lazy val commonSettings = Seq(
  organization := "Dwolla",
  homepage := Option(url("https://stash.dwolla.net/projects/OPS/repos/cloudflare-public-hostname-lambda")),
)

lazy val specs2Version = "4.3.0"
lazy val awsSdkVersion = "1.11.475"

lazy val `cloudflare-public-hostname-lambda` = (project in file("."))
  .settings(
    name := "cloudflare-public-hostname-lambda",
    resolvers ++= Seq(
      Resolver.bintrayRepo("dwolla", "maven")
    ),
    libraryDependencies ++= {
      val fs2AwsVersion = "1.3.0"

      Seq(
        "com.dwolla" %% "scala-cloudformation-custom-resource" % "2.1.0",
        "com.dwolla" %% "fs2-aws" % fs2AwsVersion,
        "io.circe" %% "circe-fs2" % "0.9.0",
        "com.dwolla" %% "cloudflare-api-client" % "4.0.0-M4",
        "org.http4s" %% "http4s-blaze-client" % "0.18.21",
        "com.amazonaws" % "aws-java-sdk-kms" % awsSdkVersion,
        "org.apache.httpcomponents" % "httpclient" % "4.5.2",
        "org.specs2" %% "specs2-core" % specs2Version % Test,
        "org.specs2" %% "specs2-mock" % specs2Version % Test,
        "org.specs2" %% "specs2-matcher-extra" % specs2Version % Test,
        "com.dwolla" %% "testutils-specs2" % "1.11.0" % Test exclude("ch.qos.logback", "logback-classic"),
        "com.dwolla" %% "fs2-aws-testkit" % fs2AwsVersion % Test,
      )
    },
    updateOptions := updateOptions.value.withCachedResolution(false),
  )
  .settings(commonSettings: _*)
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .enablePlugins(PublishToS3)

lazy val stack: Project = (project in file("stack"))
  .settings(commonSettings: _*)
  .settings(
    resolvers ++= Seq(Resolver.jcenterRepo),
    libraryDependencies ++= {
      val scalaAwsUtilsVersion = "1.6.1"

      Seq(
        "com.monsanto.arch" %% "cloud-formation-template-generator" % "3.5.4",
        "org.specs2" %% "specs2-core" % specs2Version % "test,it",
        "com.amazonaws" % "aws-java-sdk-cloudformation" % awsSdkVersion % IntegrationTest,
        "com.dwolla" %% "scala-aws-utils" % scalaAwsUtilsVersion % IntegrationTest,
      )
    },
    stackName := (name in `cloudflare-public-hostname-lambda`).value,
    stackParameters := List(
      "S3Bucket" → (s3Bucket in `cloudflare-public-hostname-lambda`).value,
      "S3Key" → (s3Key in `cloudflare-public-hostname-lambda`).value
    ),
    awsAccountId := sys.props.get("AWS_ACCOUNT_ID"),
    awsRoleName := Option("cloudformation/deployer/cloudformation-deployer"),
    scalacOptions --= Seq(
      "-Xlint:missing-interpolator",
      "-Xlint:option-implicit",
    ),
  )
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .enablePlugins(CloudFormationStack)
  .dependsOn(`cloudflare-public-hostname-lambda`)

assemblyMergeStrategy in assembly := {
  case PathList(ps @ _*) if ps.last == "Log4j2Plugins.dat" => sbtassembly.Log4j2MergeStrategy.plugincache
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("log4j2.xml") => MergeStrategy.singleOrError
  case _ ⇒ MergeStrategy.first
}
test in assembly := {}
