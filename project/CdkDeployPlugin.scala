import io.chrisdavenport.npmpackage.sbtplugin.NpmPackagePlugin.autoImport.*
import com.github.sbt.git.SbtGit.git
import feral.lambda.sbt.LambdaJSPlugin
import sbt.Keys.*
import sbt.internal.util.complete.DefaultParsers.*
import sbt.internal.util.complete.Parser
import sbt.{Def, settingKey, IO as _, *}

object CdkDeployPlugin extends AutoPlugin {
  object autoImport {
    val cdkDeployCommand = settingKey[Seq[String]]("cdk command to deploy the application")
    val deploy = inputKey[DeployOutcome]("deploy to AWS")
    val cdkSynth = taskKey[File]("synthesize the CloudFormation template locally without deploying to AWS")
  }

  import autoImport.*

  override def trigger: PluginTrigger = NoTrigger

  override def requires: Plugins = LambdaJSPlugin

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    cdkDeployCommand := "npm --prefix cdk run deploy --verbose".split(' ').toSeq,
    cdkSynth := {
      val log = streams.value.log

      // build the Lambda artifact so the CDK asset directory actually exists on disk
      val _ = (Compile / npmPackage).value
      val assets = (Compile / npmPackageOutputDirectory).value

      // synth makes no AWS calls, so real credentials aren't required; fall back to
      // placeholder account/region so the template can be generated anywhere.
      val account = sys.env.getOrElse("CDK_DEFAULT_ACCOUNT", "000000000000")
      val region = sys.env.getOrElse("CDK_DEFAULT_REGION", "us-east-1")

      val assembly = LambdaStack(
        name = "Function",
        handler = "CloudflareDnsRecordHandler",
        assets = assets,
        account = account,
        region = region,
        outputDir = target.value / "cdk.out",
      ).synth()

      val outputDir = file(assembly.getDirectory)
      log.info(s"Synthesized CloudFormation template(s) to $outputDir")
      outputDir
        .listFiles()
        .filter(_.getName.endsWith(".template.json"))
        .foreach(template => log.info(s"  $template"))

      outputDir
    },
    deploy := Def.inputTask {
      import scala.sys.process.*

      val baseCommand = cdkDeployCommand.value
      val deployProcess = Process(
        baseCommand ++ Seq("--stage", Stage.parser.parsed.name),
        Option((ThisBuild / baseDirectory).value),
        "ARTIFACT_PATH" -> (Compile / npmPackageOutputDirectory).value.toString,
        "VERSION" -> version.value,
        "VCS_URL" -> (ThisBuild / homepage).value.get.toString,
      )

      if (taggedVersion.value.exists(_.toString == version.value)) {
        if (deployProcess.! == 0) Success
        else throw new IllegalStateException("Serverless returned a non-zero exit code. Please check the logs for more information.")
      } else SkippedBecauseVersionIsNotLatestTag(version.value, taggedVersion.value)
    }.evaluated
  )

  sealed abstract class Stage(val name: String) {
    val parser: Parser[this.type] = (Space ~> token(this.toString)).map(_ => this)
  }

  object Stage {
    val parser: Parser[Stage] =
        token(Stage.Admin.parser) |
        token(Stage.Sandbox.parser)

    case object Admin extends Stage("admin")
    case object Sandbox extends Stage("sandbox")
  }

  private def taggedVersion: Def.Initialize[Option[Version]] = Def.setting {
    git.gitCurrentTags.value.collect { case Version.Tag(v) => v }.sorted.lastOption
  }

  sealed trait DeployOutcome // no failed outcome because we just throw an exception in that case
  case object Success extends DeployOutcome
  case class SkippedBecauseVersionIsNotLatestTag(version: String, taggedVersion: Option[Version]) extends DeployOutcome

}
