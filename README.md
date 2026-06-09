# Cloudflare DNS Record Handler

[![Travis](https://img.shields.io/travis/Dwolla/cloudflare-public-hostname-lambda.svg?style=flat-square)](https://travis-ci.org/Dwolla/cloudflare-public-hostname-lambda)
![license](https://img.shields.io/github/license/Dwolla/cloudflare-public-hostname-lambda.svg?style=flat-square)

An AWS CloudFormation custom resource that manages a Cloudflare DNS Record.

To run all tests:

```ShellSession
sbt clean 'testOnly -- timefactor 10' 'stack/testOnly -- timefactor 10' stack/it:test
```

## Deploy

This project uses the [AWS CDK](https://docs.aws.amazon.com/cdk/v2/guide/home.html) to
synthesize and deploy the Lambda and its CloudFormation stack. The commands below are
provided by the `CdkDeployPlugin` sbt plugin, which drives the CDK CLI via
`npx --yes aws-cdk@2` — so a local Node.js/npm is required, but the CDK CLI itself does
not need to be installed globally.

### Commands

| Command | Description | AWS credentials |
|---------|-------------|-----------------|
| `sbt cdkSynth` | Builds the Lambda artifact and synthesizes the CloudFormation template to `target/cdk.out`. Makes no AWS calls. | Not required |
| `sbt 'cdkDiff <stage>'` | Synthesizes the stack and shows the diff against what is currently deployed. | Required |
| `sbt 'show deploy <stage>'` | Synthesizes and deploys the stack to AWS. | Required |

`<stage>` is one of `Admin` or `Sandbox` (case-insensitive).

### Target account & region

`cdkDiff` and `deploy` resolve the target environment from two environment variables and
confirm it against your ambient AWS credentials. Both must be set to real values before
deploying or diffing:

```ShellSession
export CDK_DEPLOY_ACCOUNT=
export CDK_DEPLOY_REGION=us-west-2
sbt 'show deploy Admin'
```

`cdkSynth` makes no AWS calls, so it falls back to placeholder values when these are
unset. The target account must have been bootstrapped for the CDK (`cdk bootstrap`) once
before the first deploy.

### Deploy gating & output

The `deploy` task only deploys when the current build's version matches the latest git
tag on `HEAD`; otherwise it is a no-op and reports `SkippedBecauseVersionIsNotLatestTag`.
Wrapping the task in sbt's [`show`](https://www.scala-sbt.org/1.x/docs/Inspecting-Settings.html#show)
prints the resulting outcome (`Success` or `Skipped…`) to the build log.

In CI, deploys run automatically from the `deployProd` stage in [`.dwollaci.yml`](.dwollaci.yml).

## CloudFormation Custom Resource

Here is an example of how to include this as a custom resource in a CloudFormation stack.

```json
{
  "Parameters": {
    "CloudflareEmail": {
      "Description": "Email address of the account that can interact with the Cloudflare API",
      "Type": "String"
    },
    "CloudflareKey": {
      "Description": "Cloudflare API Key",
      "NoEcho": true,
      "Type": "String"
    }
  },
  "Resources": {
    "CloudflareRecord": {
      "Properties": {
        "Name": "example.dwolla.net",
        "Content": "example.us-west-2.sandbox.dwolla.net",
        "Type": "CNAME",
        "TTL": 42,
        "Proxied": true,

        "CloudflareEmail": {
          "Ref": "CloudflareEmail"
        },
        "CloudflareKey": {
          "Ref": "CloudflareKey"
        },
        "ServiceToken": {
          "Fn::ImportValue": "CloudflareDnsRecordLambda"
        }
      },
      "Type": "Custom::CloudflareDnsRecord"
    }
  }
}
```

There are five primary parameters defining the DNS record:

|Parameter Name|Type|Notes|
|--------------|----|-----|
|`Name`|String|The public-facing name of the DNS record. This is what can be resolved.|
|`Content`|String|This is the value of the record. For an `A` record, this should be an IP address. For a `CNAME`, it should be a hostname.|
|`Type`|one of: `A`, `CNAME`, or the other supported Cloudflare record types|May not be modified without deleting the existing record|
|`TTL`|Integer (seconds)|Optional TTL; if not set, Cloudflare assigns an automatic TTL|
|`Proxied`|boolean|Optional; indicates whether requests should be proxied through Cloudflare’s DDoS service.|
