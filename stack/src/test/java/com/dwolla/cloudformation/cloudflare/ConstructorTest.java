package com.dwolla.cloudformation.cloudflare;

import com.dwolla.lambda.cloudflare.record.CloudflareDnsRecordHandler;

public class ConstructorTest {

    // This needs to compile for the Lambda to be constructable at AWS
    final CloudflareDnsRecordHandler handler = new CloudflareDnsRecordHandler();

}
