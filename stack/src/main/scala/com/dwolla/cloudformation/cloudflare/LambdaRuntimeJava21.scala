package com.dwolla.cloudformation.cloudflare

import com.monsanto.arch.cloudformation.model.resource.Runtime

/** `java21` is not yet a case object in cloud-formation-template-generator 3.5.x; mirror the library pattern. */
case object LambdaRuntimeJava21 extends Runtime("java21")
