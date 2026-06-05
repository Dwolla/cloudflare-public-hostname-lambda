import sbt.File
import software.amazon.awscdk.services.iam.{ArnPrincipal, PolicyStatement, ServicePrincipal}
import software.amazon.awscdk.{App, CfnOutput, Duration, Environment, Fn, Stack, StackProps}
import software.amazon.awscdk.services.lambda.*
import software.amazon.awscdk.services.kms.*
import software.constructs.Construct

import scala.jdk.CollectionConverters.*
import scala.util.chaining.*

object LambdaStack {
  def apply(name: String,
            handler: String,
            assets: File,
            account: String,
            region: String,
            outputDir: File = new File("cdk.out"),
           ): App = {
    val environment = Environment
      .builder()
      .account(account)
      .region(region)
      .build()

    App.Builder.create()
      .outdir(outputDir.getPath)
      .build()
      .tap {
        new LambdaStack(_, "cloudflare-public-hostname-lambda", StackProps.builder().env(environment).build())(name, handler, assets)
      }
  }

}

class LambdaStack(scope: Construct,
                  id: String,
                  props: StackProps)
                 (name: String,
                  handler: String,
                  assets: File,
                 )
  extends Stack(scope, id, props) {

  val function: Function = Function.Builder
    .create(this, name)
    .runtime(Runtime.NODEJS_22_X)
    .timeout(Duration.seconds(60))
    .memorySize(512)
    .handler(s"index.$handler")
    .code(Code.fromAsset(assets.getPath))
    .initialPolicy(
      List(
        PolicyStatement.Builder.create()
          .actions(List("route53:GetHostedZone").asJava)
          .resources(List("*").asJava)
          .build()
      ).asJava
    )
    .build()

  val keyAlias = "alias/CloudflarePublicDnsRecordKey"

  val kmsKey: Key = Key.Builder.create(this, "Key")
    .description("Encryption key protecting secrets for the Cloudflare public record lambda")
    .enabled(true)
    .enableKeyRotation(true)
    .alias(keyAlias)
    .build()

  kmsKey.grant(new ArnPrincipal(Fn.sub("arn:aws:iam::$${AWS::AccountId}:role/DataEncrypter")),
    "kms:Encrypt",
    "kms:ReEncrypt",
    "kms:DescribeKey",
  )

  kmsKey.grantDecrypt(new ArnPrincipal(function.getRole.getRoleArn))

  CfnOutput.Builder
    .create(this, "CloudflarePublicHostnameLambda")
    .description("ARN of the Lambda that interfaces with Cloudflare")
    .value(function.getFunctionName)
    .exportName("CloudflarePublicHostnameLambda")
    .build()

  CfnOutput.Builder
    .create(this, "CloudflarePublicHostnameLambdaKey")
    .description("KMS Key Alias for Cloudflare public DNS record lambda")
    .value(keyAlias)
    .build()
}
