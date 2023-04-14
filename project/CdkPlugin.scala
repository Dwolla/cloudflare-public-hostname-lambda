import com.typesafe.sbt.packager.universal.UniversalPlugin
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._
import sbt.Keys.{baseDirectory, packageBin}
import sbt.internal.util.complete.DefaultParsers._
import sbt.internal.util.complete.Parser
import sbt.{Def, settingKey, IO => _, _}

object CdkDeployPlugin extends AutoPlugin {
  object autoImport {
    val cdkDeployCommand = settingKey[Seq[String]]("cdk command to deploy the application")
    val deploy = taskKey[Int]("deploy to AWS")
  }

  import autoImport._

  override def trigger: PluginTrigger = NoTrigger

  override def requires: Plugins = UniversalPlugin

  override lazy val projectSettings = Seq(
    cdkDeployCommand := "npm --prefix cdk run deploy --verbose".split(' ').toSeq,
    deploy := {
      import scala.sys.process._

      val exitCode = Process(
        cdkDeployCommand.value,
        Option((ThisBuild / baseDirectory).value),
        "ARTIFACT_PATH" -> (Universal / packageBin).value.toString,
      ).!

      if (exitCode == 0) exitCode
      else throw new IllegalStateException("cdk returned a non-zero exit code. Please check the logs for more information.")
    }
  )

}