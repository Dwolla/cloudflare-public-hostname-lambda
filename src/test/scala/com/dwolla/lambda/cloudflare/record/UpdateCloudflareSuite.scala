package com.dwolla.lambda.cloudflare.record

import cats.effect.*
import cats.syntax.all.*
import com.amazonaws.kms.*
import com.dwolla.cloudflare.*
import com.dwolla.cloudflare.domain.model.*
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import munit.{CatsEffectSuite, Compare}
import natchez.Trace
import org.http4s.client.Client
import org.typelevel.log4cats.console.ConsoleLoggerFactory
import org.typelevel.log4cats.{Logger, LoggerFactory}
import org.typelevel.scalaccompat.annotation.targetName3
import smithy4s.{Bijection, Blob}

@annotation.experimental
class UpdateCloudflareSuite extends CatsEffectSuite {
  given [A, B](using Bijection[B, A], Compare[A, A]): Compare[A, B] =
    (obtained: A, expected: B) =>
      summon[Compare[A, A]].isEqual(obtained, summon[Bijection[B, A]].to(expected))

  test("CloudflareDnsRecordHandler propagates exceptions thrown by the KMS client (smithy4s)") {
    val kmsErrorMessage = "The ciphertext refers to a KMS key you cannot access"

    val failingKms: KMS[IO] = new KMS[IO] {
      def decrypt(ciphertextBlob: CiphertextType,
                  encryptionContext: Option[Map[EncryptionContextKey, EncryptionContextValue]],
                  grantTokens: Option[List[GrantTokenType]],
                  keyId: Option[KeyIdType],
                  encryptionAlgorithm: Option[EncryptionAlgorithmSpec],
                  recipient: Option[RecipientInfo],
                  dryRun: Option[NullableBooleanType]
                 ): IO[DecryptResponse] =
        IO.raiseError(InvalidCiphertextException(Option(ErrorMessageType(kmsErrorMessage))))
    }

    // minimal Client that should never be used in this test (decrypt fails first)
    val dummyClient: Client[IO] = Client[IO](_ => Resource.eval(IO.raiseError(new RuntimeException("HTTP should not be called"))))

    implicit val loggerFactory: LoggerFactory[IO] = ConsoleLoggerFactory.create[IO]
    implicit val trace: Trace[IO] = natchez.Trace.Implicits.noop

    val handler = new CloudflareDnsRecordHandler[IO](dummyClient, failingKms)

    val input = DnsRecordWithCredentials(
      dnsRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "new-example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      ),
      cloudflareEmail = CiphertextType(Blob("cloudflare-account-email@dwollalabs.com".getBytes("UTF-8"))),
      cloudflareKey = CiphertextType(Blob("fake-key".getBytes("UTF-8")))
    )

    interceptMessageIO[InvalidCiphertextException](kmsErrorMessage) {
      handler.updateResource(input, feral.lambda.cloudformation.PhysicalResourceId.unsafeApply("different-physical-id"))
    }
  }

  test("create specified CNAME record") {
    implicit val loggerFactory: LoggerFactory[IO] = ConsoleLoggerFactory.create[IO]
    implicit val logger: Logger[IO] = loggerFactory.getLogger
    implicit val trace: Trace[IO] = natchez.Trace.Implicits.noop
    val inputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )
    val expectedRecord = IdentifiedDnsRecord(
      physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"),
      zoneId = ZoneId("fake-zone-id"),
      resourceId = ResourceId("fake-resource-id"),
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )

    val fakeCloudflareClient: FakeDnsRecordClient = new FakeDnsRecordClient {
      override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
        if (record == inputRecord) Stream.emit(expectedRecord)
        else Stream.raiseError[IO](new RuntimeException(s"unexpected argument: $record"))

      override def getExistingDnsRecords(name: String,
                                         content: Option[String],
                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
        if (name == "example.dwolla.com" && recordType.contains("CNAME")) Stream.empty
        else Stream.raiseError[IO](new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
    }

    val output = UpdateCloudflare(fakeCloudflareClient).handleCreateOrUpdate(inputRecord, None)

    output.flatMap { handlerResponse =>
      IO {
        assertEquals(handlerResponse.physicalId.value, "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")
        assertEquals(handlerResponse.data.get.apply("dnsRecord"), Some(expectedRecord.asJson))
        assertEquals(handlerResponse.data.get.apply("created"), Some(expectedRecord.asJson))
        assertEquals(handlerResponse.data.get.apply("updated"), Some(None.asJson))
        assertEquals(handlerResponse.data.get.apply("oldDnsRecord"), Some(None.asJson))
      }
    }
  }

  test("update a non-CNAME DNS record if it already exists, with physical ID from CloudFormation") {
    implicit val loggerFactory: LoggerFactory[IO] = ConsoleLoggerFactory.create[IO]
    implicit val logger: Logger[IO] = loggerFactory.getLogger
    implicit val trace: Trace[IO] = natchez.Trace.Implicits.noop
    val physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")

    val inputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "MX",
      ttl = Option(42),
      proxied = Option(true),
      priority = Option(10),
    )
    val existingRecord = IdentifiedDnsRecord(
      physicalResourceId = physicalResourceId,
      zoneId = ZoneId("fake-zone-id"),
      resourceId = ResourceId("fake-resource-id"),
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "MX",
      ttl = Option(42),
      proxied = None,
      priority = Option(10),
    )

    val expectedRecord = existingRecord.copy(content = "new-example.dwollalabs.com")

    val fakeCloudflareClient = new FakeDnsRecordClient {
      override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
        if (inputRecord.identifyAs(physicalResourceId).contains(record)) Stream.emit(expectedRecord)
        else Stream.raiseError[IO](new RuntimeException(s"unexpected argument: $record"))

      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
        if (physicalResourceId == existingRecord.physicalResourceId) Stream.emit(existingRecord)
        else Stream.raiseError[IO](new RuntimeException(s"unexpected arguments: ($physicalResourceId)"))
    }

    val output = UpdateCloudflare(fakeCloudflareClient).handleCreateOrUpdate(inputRecord, physicalResourceIdBijection.to(physicalResourceId).some)

    output.flatMap { handlerResponse =>
      IO {
        assertEquals(handlerResponse.physicalId, expectedRecord.physicalResourceId)
        assertEquals(handlerResponse.data.get.apply("dnsRecord"), Some(expectedRecord.asJson))
        assertEquals(handlerResponse.data.get.apply("oldDnsRecord"), Some(existingRecord.asJson))
      }
    }
  }

  test("delete a DNS record if requested") {
    implicit val loggerFactory: LoggerFactory[IO] = ConsoleLoggerFactory.create[IO]
    implicit val logger: Logger[IO] = loggerFactory.getLogger
    implicit val trace: Trace[IO] = natchez.Trace.Implicits.noop
    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
    val inputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )
    val existingRecord = inputRecord.identifyAs(physicalResourceId)

    val fakeDnsRecordClient: FakeDnsRecordClient = new FakeDnsRecordClient {
      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
        Stream.emit(existingRecord).unNone

      override def deleteDnsRecord(physicalResourceId: String): Stream[IO, PhysicalResourceId] =
        Stream.emit(PhysicalResourceId(physicalResourceId))
    }

    val output = UpdateCloudflare(fakeDnsRecordClient).handleDelete(feral.lambda.cloudformation.PhysicalResourceId.unsafeApply(physicalResourceId))

    output.flatMap { handlerResponse =>
      IO {
        assertEquals(handlerResponse.physicalId.value, physicalResourceId)
        assertEquals(handlerResponse.data.get.apply("deletedRecordId"), Some(physicalResourceId.asJson))
      }
    }
  }
}
