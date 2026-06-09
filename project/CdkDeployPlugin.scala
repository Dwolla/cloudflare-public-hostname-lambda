import io.chrisdavenport.npmpackage.sbtplugin.NpmPackagePlugin.autoImport.*
import com.github.sbt.git.SbtGit.git
import feral.lambda.sbt.LambdaJSPlugin
import sbt.Keys.*
import sbt.internal.util.complete.DefaultParsers.*
import sbt.internal.util.complete.Parser
import sbt.util.Logger
import sbt.{Def, settingKey, IO as _, *}

object CdkDeployPlugin extends AutoPlugin {
  object autoImport {
    val cdkCliCommand = settingKey[Seq[String]]("base command used to invoke the AWS CDK CLI")
    val cdkSynth = taskKey[File]("synthesize the CloudFormation template locally without deploying to AWS")
    val cdkDiff = inputKey[Unit]("dry-run: show the diff between the proposed stack and the deployed stack")
    val deploy = inputKey[DeployOutcome]("deploy to AWS")
  }

  import autoImport.*

  override def trigger: PluginTrigger = NoTrigger

  override def requires: Plugins = LambdaJSPlugin

  private val StackName = "cloudflare-public-hostname-lambda"

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    // The `aws-cdk` CLI now versions independently of `aws-cdk-lib` (the CLI is at 2.10xx
    // while the construct library is 2.220.0). The CLI is backward-compatible with older
    // cloud-assembly schemas, so a current 2.x CLI can deploy an assembly produced by
    // aws-cdk-lib 2.220.0. Pin an exact CLI version here if reproducible builds are needed.
    cdkCliCommand := Seq("npx", "--yes", "aws-cdk@2"),
    cdkSynth := {
      val log = streams.value.log

      // build the Lambda artifact so the CDK asset directory actually exists on disk
      val _ = (Compile / npmPackage).value
      val assets = (Compile / npmPackageOutputDirectory).value

      // synth makes no AWS calls, so real credentials aren't required; fall back to
      // placeholder account/region so the template can be generated anywhere.
      val account = sys.env.getOrElse("CDK_DEPLOY_ACCOUNT", "000000000000")
      val region = sys.env.getOrElse("CDK_DEPLOY_REGION", "us-west-2")

      synthAssembly(account, region, assets, target.value / "cdk.out", log)
    },
    cdkDiff := Def.inputTask {
      val log = streams.value.log

      // parse (and validate) the stage argument even though the CDK CLI resolves the
      // target account/region from the ambient credential chain
      val stage = Stage.resolve(Stage.parser.parsed)

      val packaged = (Compile / npmPackage).value
      val assets = (Compile / npmPackageOutputDirectory).value
      val (account, region) = requireDeployEnv(log)
      val assembly = synthAssembly(account, region, assets, target.value / "cdk.out", log)

      runCdk(cdkCliCommand.value, Seq("diff", StackName), assembly, log)
    }.evaluated,
    deploy := Def.inputTask {
      val log = streams.value.log

      // parse (and validate) the stage argument; preserves the existing admin/sandbox interface
      val stage = Stage.resolve(Stage.parser.parsed)

      if (taggedVersion.value.exists(_.toString == version.value)) {
        val packaged = (Compile / npmPackage).value
        val assets = (Compile / npmPackageOutputDirectory).value
        val (account, region) = requireDeployEnv(log)
        val assembly = synthAssembly(account, region, assets, target.value / "cdk.out", log)

        val exitCode = runCdk(
          cdkCliCommand.value,
          Seq("deploy", StackName, "--require-approval", "never"),
          assembly,
          log,
        )

        if (exitCode == 0) Success
        else throw new IllegalStateException("The CDK CLI returned a non-zero exit code. Please check the logs for more information.")
      } else SkippedBecauseVersionIsNotLatestTag(version.value, taggedVersion.value)
    }.evaluated
  )

  private def synthAssembly(account: String,
                            region: String,
                            assets: File,
                            outputDir: File,
                            log: Logger,
                           ): File = {
    val assembly = LambdaStack(
      name = "Function",
      handler = "CloudflareDnsRecordHandler",
      assets = assets,
      account = account,
      region = region,
      outputDir = outputDir,
    ).synth()

    val synthesizedDir = file(assembly.getDirectory)
    log.info(s"Synthesized CloudFormation template(s) to $synthesizedDir")
    synthesizedDir
      .listFiles()
      .filter(_.getName.endsWith(".template.json"))
      .foreach(template => log.info(s"  $template"))

    synthesizedDir
  }

  /**
   * Resolve the AWS account/region for a real deploy or diff. The CDK CLI uses these to
   * confirm the synthesized environment matches the ambient credentials, so a placeholder
   * account must never be used here.
   */
  private def requireDeployEnv(log: Logger): (String, String) = {
    val account = sys.env.getOrElse("CDK_DEPLOY_ACCOUNT", "")
    val region = sys.env.getOrElse("CDK_DEPLOY_REGION", "")

    if (account.isEmpty || account == "000000000000")
      throw new MessageOnlyException("CDK_DEPLOY_ACCOUNT must be set to the real target AWS account before deploying or diffing.")
    if (region.isEmpty)
      throw new MessageOnlyException("CDK_DEPLOY_REGION must be set to the real target AWS region before deploying or diffing.")

    log.info(s"Targeting AWS account $account in region $region")
    account -> region
  }

  private def runCdk(cliCommand: Seq[String],
                     args: Seq[String],
                     assembly: File,
                     log: Logger,
                    ): Int = {
    import scala.sys.process.*

    val command = cliCommand ++ args ++ Seq("--app", assembly.getPath)
    log.info(s"Running: ${command.mkString(" ")}")
    command.!
  }

  sealed abstract class Stage(val name: String)

  object Stage {
    case object Admin extends Stage("admin")
    case object Sandbox extends Stage("sandbox")

    val all: Seq[Stage] = Seq(Admin, Sandbox)

    // Parse the stage argument as optional so the task body can emit a helpful message
    // listing the valid stages when it's missing — sbt's built-in failure for a required
    // argument is only the terse "Expected whitespace character". `.examples` still drives
    // tab-completion to suggest `Admin`/`Sandbox`.
    val parser: Parser[Option[String]] =
      (Space ~> token(StringBasic, "<stage>").examples(all.map(_.toString)*)).?

    /** Resolve the parsed argument to a Stage (case-insensitive), or fail listing the valid stages. */
    def resolve(arg: Option[String]): Stage = {
      val valid = all.map(_.toString).mkString(", ")
      arg
        .flatMap(a => all.find(_.name.equalsIgnoreCase(a)))
        .getOrElse(throw new MessageOnlyException(
          arg.fold(s"A deploy stage is required. Valid stages: $valid")(
            a => s"'$a' is not a valid stage. Valid stages: $valid")))
    }
  }

  private def taggedVersion: Def.Initialize[Option[Version]] = Def.setting {
    git.gitCurrentTags.value.collect { case Version.Tag(v) => v }.sorted.lastOption
  }

  sealed trait DeployOutcome // no failed outcome because we just throw an exception in that case
  case object Success extends DeployOutcome
  case class SkippedBecauseVersionIsNotLatestTag(version: String, taggedVersion: Option[Version]) extends DeployOutcome

}
