import sbt.File
import software.amazon.awscdk.services.iam.{ArnPrincipal, CfnRole, PolicyStatement, ServicePrincipal}
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

    val lambdaStackProps = StackProps
      .builder()
      .env(environment)
      .description("cloudflare-public-hostname-lambda lambda function and supporting resources")
      .build()

    App.Builder.create()
      .outdir(outputDir.getPath)
      .build()
      .tap {
        new LambdaStack(_, "cloudflare-public-hostname-lambda", lambdaStackProps)(name, handler, assets)
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
    .description("Creates or updates a public hostname at Cloudflare zone")
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

  // Match the logical IDs of the resources that already exist in the deployed (raw
  // CloudFormation) stack so CloudFormation updates them in place instead of replacing them.
  // Replacement would destroy the KMS key (and the secrets it protects) and change the
  // exported Lambda value that downstream stacks import.
  function.getNode.getDefaultChild.asInstanceOf[CfnFunction].overrideLogicalId("Function")
  function.getRole.getNode.getDefaultChild.asInstanceOf[CfnRole].overrideLogicalId("Role")

  val keyAlias = "alias/CloudflarePublicDnsRecordKey"

  val kmsKey: Key = Key.Builder.create(this, "Key")
    .description("Encryption key protecting secrets for the Cloudflare public record lambda")
    .enabled(true)
    .enableKeyRotation(true)
    .build()

  kmsKey.getNode.getDefaultChild.asInstanceOf[CfnKey].overrideLogicalId("Key")

  // Create the alias as an explicit construct (rather than the Key's `.alias(...)` prop) so we
  // can pin its logical ID to the deployed `KeyAlias`. Leaving it hashed would make CDK try to
  // create a second alias with the same name and fail on the existing-alias conflict.
  val alias: software.amazon.awscdk.services.kms.Alias =
    software.amazon.awscdk.services.kms.Alias.Builder.create(this, "Alias")
      .aliasName(keyAlias)
      .targetKey(kmsKey)
      .build()

  alias.getNode.getDefaultChild.asInstanceOf[software.amazon.awscdk.services.kms.CfnAlias].overrideLogicalId("KeyAlias")

  kmsKey.grant(new ArnPrincipal(Fn.sub("arn:aws:iam::${AWS::AccountId}:role/DataEncrypter")),
    "kms:Encrypt",
    "kms:ReEncrypt",
    "kms:DescribeKey",
  )

  kmsKey.grantDecrypt(new ArnPrincipal(function.getRole.getRoleArn))

  // Safety: the deployed key policy grants the cloudformation-deployer role full KMS
  // management (CloudFormationDeploymentRoleOwnsKey). Keep it until the new pipeline's deploy
  // principal is confirmed, so we don't lock future updates out of the key.
  kmsKey.grant(new ArnPrincipal(Fn.sub("arn:aws:iam::$${AWS::AccountId}:role/cloudformation/deployer/cloudformation-deployer")),
    "kms:Create*",
    "kms:Describe*",
    "kms:Enable*",
    "kms:List*",
    "kms:Put*",
    "kms:Update*",
    "kms:Revoke*",
    "kms:Disable*",
    "kms:Get*",
    "kms:Delete*",
    "kms:ScheduleKeyDeletion",
    "kms:CancelKeyDeletion",
  )

  CfnOutput.Builder
    .create(this, "CloudflarePublicHostnameLambda")
    .description("ARN of the Lambda that interfaces with Cloudflare")
    .value(function.getFunctionArn)
    .exportName("CloudflarePublicHostnameLambda")
    .build()

  CfnOutput.Builder
    .create(this, "CloudflarePublicHostnameKey")
    .description("KMS Key Alias for Cloudflare public DNS record lambda")
    .value(keyAlias)
    .build()
}
