import {
  App,
  CfnOutput,
  Duration,
  Fn,
  Stack,
  StackProps,
  aws_ec2,
  aws_iam,
  aws_kms,
  aws_lambda
} from 'aws-cdk-lib';
import { Effect } from 'aws-cdk-lib/aws-iam';
import { Code, Runtime } from 'aws-cdk-lib/aws-lambda';

export default class CloudflarePublicHostnameStack extends Stack {
  constructor(app: App, id: string, props: StackProps) {
    super(app, id, props);

    const cloudflareLambda = new aws_lambda.Function(this, 'Function', {
      code: Code.fromAsset(`${__dirname}/${process.env.ARTIFACT_PATH}`),
      runtime: Runtime.JAVA_8,
      functionName: 'cloudflare-public-hostname-lambda-Function-1KC7WIOVMTBR',
      memorySize: 512,
      timeout: Duration.seconds(60),
      handler: 'com.dwolla.lambda.cloudflare.record.CloudflareDnsRecordHandler',
      initialPolicy: [
        new aws_iam.PolicyStatement({
          effect: Effect.ALLOW,
          actions: ['route53:GetHostedZone'],
          resources: ['*']
        })
      ]
    });

    const keyAlias = 'alias/CloudflarePublicDnsRecordKey';

    const kmsKey = new aws_kms.Key(this, 'Key', {
      description:
        'Encryption key protecting secrets for the Cloudflare public record lambda',
      enabled: true,
      enableKeyRotation: true,
      alias: keyAlias
    });
    kmsKey.grant(
      new aws_iam.ArnPrincipal(
        Fn.sub('arn:aws:iam::${AWS::AccountId}:role/DataEncrypter')
      ),
      'kms:Encrypt',
      'kms:ReEncrypt',
      'kms:DescribeKey'
    );

    kmsKey.grantDecrypt(
      new aws_iam.ArnPrincipal(cloudflareLambda.role.roleArn)
    );

    new CfnOutput(this, 'CloudflarePublicHostnameLambda', {
      description: 'ARN of the Lambda that interfaces with Cloudflare',
      value: cloudflareLambda.functionName,
      exportName: 'CloudflarePublicHostnameLambda'
    });

    new CfnOutput(this, 'CloudflarePublicHostnameKey', {
      description: 'KMS Key Alias for Cloudflare public DNS record lambda',
      value: keyAlias
    });
  }
}
