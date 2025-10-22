package com.dwolla.lambda.cloudflare.record

import cats.*
import cats.effect.*
import cats.syntax.all.*
import cats.tagless.aop.*
import cats.tagless.syntax.all.*
import com.amazonaws.kms.*
import com.dwolla.cloudflare.*
import com.dwolla.cloudflare.domain.model
import com.dwolla.cloudflare.domain.model.*
import com.dwolla.cloudflare.domain.model.Exceptions.*
import feral.lambda.cloudformation
import fs2.Stream
import io.circe.*
import io.circe.syntax.*
import munit.{CatsEffectSuite, Compare}
import natchez.Trace
import org.http4s.client.Client
import org.typelevel.log4cats.testing.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import smithy4s.{Bijection, Blob}

import scala.util.control.NoStackTrace

@annotation.experimental
class UpdateCloudflareSuite extends CatsEffectSuite {
  given [A, B](using Bijection[B, A], Compare[A, A]): Compare[A, B] =
    (obtained: A, expected: B) =>
      summon[Compare[A, A]].isEqual(obtained, summon[Bijection[B, A]].to(expected))

  private def stub[Alg[_[_]] : Instrument, F[_] : ApplicativeThrow](alg: Alg[F]): Alg[F] =
    alg.instrument.mapK(new EnhancedStubWithInstrumentation)

  test("CloudflareDnsRecordHandler propagates exceptions thrown by the KMS client (smithy4s)") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Trace[IO] = natchez.Trace.Implicits.noop

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

    val handler = new CloudflareDnsRecordHandler[IO](dummyClient, failingKms, DnsRecordClient(_))

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
      handler.updateResource(input, cloudformation.PhysicalResourceId.unsafeApply("different-physical-id"))
    }
  }

  test("create specified CNAME record") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

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

    val fakeCloudflareClient = new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
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
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

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

    val fakeCloudflareClient = new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
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
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
    val inputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )
    val existingRecord = inputRecord.identifyAs(physicalResourceId)

    val fakeDnsRecordClient = new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
        Stream.emit(existingRecord).unNone

      override def deleteDnsRecord(physicalResourceId: String): Stream[IO, PhysicalResourceId] =
        Stream.emit(PhysicalResourceId(physicalResourceId))
    }

    val output = UpdateCloudflare(fakeDnsRecordClient).handleDelete(cloudformation.PhysicalResourceId.unsafeApply(physicalResourceId))

    output.flatMap { handlerResponse =>
      IO {
        assertEquals(handlerResponse.physicalId.value, physicalResourceId)
        assertEquals(handlerResponse.data.get.apply("deletedRecordId"), Some(physicalResourceId.asJson))
      }
    }
  }

  test("UpdateCloudflare create should propagate exceptions thrown by the Cloudflare client") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val expectedInputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )

    val fakeDnsRecordClient = new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def getExistingDnsRecords(name: String, content: Option[String], recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] = Stream.empty

      override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
        if (record == expectedInputRecord) Stream.raiseError(NoStackTraceException)
        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))
    }

    interceptMessageIO[NoStackTraceException.type](NoStackTraceException.getMessage) {
      UpdateCloudflare(fakeDnsRecordClient).handleCreateOrUpdate(expectedInputRecord, None)
    }
  }

  test("UpdateCloudflare create should propagate exception if fetching existing records fails") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val inputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )

    val fakeDnsRecordClient = new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def getExistingDnsRecords(name: String,
                                         content: Option[String],
                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
        Stream.raiseError(NoStackTraceException)
    }

    interceptMessageIO[NoStackTraceException.type](NoStackTraceException.getMessage) {
      UpdateCloudflare(fakeDnsRecordClient).handleCreateOrUpdate(inputRecord, None)
    }
  }

  test("UpdateCloudflare create should create a CNAME record if it doesn't exist, despite having a physical ID provided by CloudFormation") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val providedPhysicalId = cloudformation.PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")
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

    val fakeDnsRecordClient = new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
        if (record == inputRecord) Stream.emit(expectedRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

      override def getExistingDnsRecords(name: String,
                                         content: Option[String],
                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
        if (name == "example.dwolla.com" && recordType.contains("CNAME")) Stream.empty
        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
    }

    UpdateCloudflare(fakeDnsRecordClient)
      .handleCreateOrUpdate(inputRecord, providedPhysicalId)
      .flatMap { resp =>
        IO {
          assertEquals(resp.physicalId, expectedRecord.physicalResourceId)
          assert(resp.data.exists(_.apply("dnsRecord").contains(expectedRecord.asJson)))
          assert(!resp.data.exists(_.apply("oldDnsRecord").isEmpty))
        }
      }
  }

  test("UpdateCloudflare create should create a DNS record that isn't an CNAME even if record(s) with the same name already exist") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val inputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "MX",
      ttl = Option(42),
      proxied = Option(true),
      priority = Option(10),
    )
    val expectedRecord = IdentifiedDnsRecord(
      physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"),
      zoneId = ZoneId("fake-zone-id"),
      resourceId = ResourceId("fake-resource-id"),
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "MX",
      ttl = Option(42),
      proxied = Option(true),
      priority = Option(10),
    )

    val fakeDnsRecordClient = new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
        if (record == inputRecord) Stream.emit(expectedRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

      override def getExistingDnsRecords(name: String,
                                         content: Option[String],
                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
        Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
    }

    UpdateCloudflare(fakeDnsRecordClient)
      .handleCreateOrUpdate(inputRecord, None)
      .flatMap { resp =>
        IO {
          assertEquals(resp.physicalId, expectedRecord.physicalResourceId)
          assert(resp.data.exists(_.apply("dnsRecord").contains(expectedRecord.asJson)))
          assert(resp.data.exists(_.apply("created").contains(expectedRecord.asJson)))
          assert(!resp.data.exists(_.apply("updated").isEmpty))
          assert(!resp.data.exists(_.apply("oldDnsRecord").isEmpty))
        }
      }
  }

  test("UpdateCloudflare create should pretend to have created a DNS record that isn't an CNAME if Cloudflare complains that the record already exists") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val inputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "MX",
      ttl = Option(42),
      proxied = Option(true),
      priority = Option(10),
    )
    val expectedRecord = IdentifiedDnsRecord(
      physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"),
      zoneId = ZoneId("fake-zone-id"),
      resourceId = ResourceId("fake-resource-id"),
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "MX",
      ttl = Option(42),
      proxied = Option(true),
      priority = Option(10),
    )
    val existingRecord = expectedRecord.copy(
      physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/different-record"),
      resourceId = ResourceId("different-record"),
      content = "different-content",
      priority = Option(0),
    )

    val fakeDnsRecordClient = new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
        if (record == inputRecord) Stream.raiseError(RecordAlreadyExists)
        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

      override def getExistingDnsRecords(name: String,
                                         content: Option[String],
                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
        if (name == existingRecord.name) Stream.emit(existingRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
    }

    UpdateCloudflare(fakeDnsRecordClient)
      .handleCreateOrUpdate(inputRecord, None)
      .flatMap { resp =>
        IO {
          assertEquals(resp.physicalId, existingRecord.physicalResourceId)
          assert(resp.data.exists(_.apply("dnsRecord").contains(existingRecord.asJson)))
          assert(resp.data.exists(_.apply("created").contains(existingRecord.asJson)))
          assert(!resp.data.exists(_.apply("updated").isEmpty))
          assert(!resp.data.exists(_.apply("oldDnsRecord").isEmpty))
        }
      }
  }

  test("UpdateCloudflare update should update a non-CNAME DNS record if it already exists, if its physical ID is passed in by CloudFormation") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

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

    val fakeDnsRecordClient = stub(new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
        if (inputRecord.identifyAs(physicalResourceId).contains(record)) Stream.emit(expectedRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected argument: expected ${inputRecord.identifyAs(physicalResourceId)} but received $record"))

      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
        if (physicalResourceId == existingRecord.physicalResourceId) Stream.emit(existingRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($physicalResourceId)"))
    })

    UpdateCloudflare(fakeDnsRecordClient)
      .handleCreateOrUpdate(inputRecord, physicalResourceIdBijection.to(physicalResourceId).some)
      .flatMap { resp =>
        IO {
          assertEquals(resp.physicalId, expectedRecord.physicalResourceId)
          assert(resp.data.exists(_.apply("dnsRecord").contains(expectedRecord.asJson)))
          assert(!resp.data.exists(_.apply("created").isEmpty))
          assert(resp.data.exists(_.apply("updated").contains(expectedRecord.asJson)))
          assert(resp.data.exists(_.apply("oldDnsRecord").contains(existingRecord.asJson)))
        }
      }
  }

  test("CloudflareDnsRecordHandler update should update a CNAME DNS record if it already exists, even if no physical ID is passed in by CloudFormation") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")
    val inputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true),
    )
    val existingRecord = IdentifiedDnsRecord(
      physicalResourceId = physicalResourceId,
      zoneId = ZoneId("fake-zone-id"),
      resourceId = ResourceId("fake-resource-id"),
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )

    val expectedRecord = existingRecord.copy(content = "new-example.dwollalabs.com")

    val fakeDnsRecordClient = stub(new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
        if (inputRecord.identifyAs(physicalResourceId).contains(record)) Stream.emit(expectedRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

      override def getExistingDnsRecords(name: String,
                                         content: Option[String],
                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
        if (name == existingRecord.name) Stream.emit(existingRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
    })

    for
      resp <- UpdateCloudflare(fakeDnsRecordClient).handleCreateOrUpdate(inputRecord, None)
      log <- summon[TestingLoggerFactory[IO]].logged
    yield
      assertEquals(resp.physicalId, expectedRecord.physicalResourceId)
      assertEquals(resp.data, JsonObject(
        "dnsRecord" -> expectedRecord.asJson,
        "created" -> None.asJson,
        "updated" -> expectedRecord.asJson,
        "oldDnsRecord" -> existingRecord.asJson,
      ).some)
      assertEquals(log, Vector(TestingLoggerFactory.Warn("com.dwolla.lambda.cloudflare.record.UpdateCloudflareSuite", """Discovered DNS record ID "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id" for hostname "example.dwolla.com", with existing content "example.dwollalabs.com". This record will be updated instead of creating a new record.""", None)))
  }

  test("CloudflareDnsRecordHandler update should update a CNAME DNS record if it already exists, even if the physical ID passed in by CloudFormation doesn't match the existing ID (returning the new ID)") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")
    val existingRecord = IdentifiedDnsRecord(
      physicalResourceId = physicalResourceId,
      zoneId = ZoneId("fake-zone-id"),
      resourceId = ResourceId("fake-resource-id"),
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )
    val expectedRecord = existingRecord.copy(content = "new-example.dwollalabs.com")
    val inputRecord = existingRecord.unidentify

    val fakeDnsRecordClient = stub(new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
        if (inputRecord.identifyAs(physicalResourceId).contains(record)) Stream.emit(expectedRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

      override def getExistingDnsRecords(name: String,
                                         content: Option[String],
                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
        if (name == existingRecord.name) Stream.emit(existingRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
    })

    for
      resp <- UpdateCloudflare(fakeDnsRecordClient).handleCreateOrUpdate(inputRecord, cloudformation.PhysicalResourceId("different-physical-id"))
      log <- summon[TestingLoggerFactory[IO]].logged
    yield
      assertEquals(resp.physicalId, expectedRecord.physicalResourceId)
      assertEquals(resp.data, JsonObject(
        "dnsRecord" -> expectedRecord.asJson,
        "created" -> None.asJson,
        "updated" -> expectedRecord.asJson,
        "oldDnsRecord" -> existingRecord.asJson,
      ).some)
      assertEquals(log, Vector(TestingLoggerFactory.Warn("com.dwolla.lambda.cloudflare.record.UpdateCloudflareSuite", """The passed physical ID "different-physical-id" does not match the discovered physical ID "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id" for hostname "example.dwolla.com". This may indicate a change to this stack's DNS entries that was not managed by CloudFormation. Updating the discovered record instead of the record passed by CloudFormation.""", None)))
  }

  test("CloudflareDnsRecordHandler update should refuse to change the record type if the input type is CNAME") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")

    val existingRecord = IdentifiedDnsRecord(
      physicalResourceId = (physicalResourceId),
      zoneId = ZoneId("fake-zone-id"),
      resourceId = ResourceId("fake-resource-id"),
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "A",
      ttl = Option(42),
      proxied = Option(true)
    )
    val inputRecord = existingRecord.unidentify.copy(content = "new-example.dwollalabs.com", recordType = "CNAME")

    val fakeDnsRecordClient = stub(new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def getExistingDnsRecords(name: String,
                                         content: Option[String],
                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
        if (name == existingRecord.name && recordType.contains("CNAME")) Stream.emit(existingRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
    })


    interceptIO[DnsRecordTypeChange] {
      UpdateCloudflare(fakeDnsRecordClient).handleCreateOrUpdate(inputRecord, physicalResourceIdBijection.to(physicalResourceId).some)
    }.map(assertEquals(_, DnsRecordTypeChange("A", "CNAME")))
  }

  test("CloudflareDnsRecordHandler update should refuse to change the record type if the input type is not CNAME") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")

    val existingRecord = IdentifiedDnsRecord(
      physicalResourceId = (physicalResourceId),
      zoneId = ZoneId("fake-zone-id"),
      resourceId = ResourceId("fake-resource-id"),
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "MX",
      ttl = Option(42),
      proxied = Option(true)
    )
    val inputRecord = existingRecord.unidentify.copy(content = "new text", recordType = "TXT")

    val fakeDnsRecordClient = stub(new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
        Stream.emit(existingRecord)
    })

    interceptIO[DnsRecordTypeChange] {
      UpdateCloudflare(fakeDnsRecordClient).handleCreateOrUpdate(inputRecord, physicalResourceIdBijection.to(physicalResourceId).some)
    }.map(assertEquals(_, DnsRecordTypeChange("MX", "TXT")))
  }

  test("CloudflareDnsRecordHandler update should propagate the failure exception if update fails") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")
    val existingRecord = IdentifiedDnsRecord(
      physicalResourceId = physicalResourceId,
      zoneId = ZoneId("fake-zone-id"),
      resourceId = ResourceId("fake-resource-id"),
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )

    val inputRecord = existingRecord.unidentify.copy(content = "new-example.dwolla.com")

    val fakeDnsRecordClient = stub(new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError(NoStackTraceException)

      override def getExistingDnsRecords(name: String,
                                         content: Option[String],
                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
        if (name == existingRecord.name && recordType.contains(existingRecord.recordType)) Stream.emit(existingRecord)
        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
    })

    interceptIO[NoStackTraceException.type] {
      UpdateCloudflare(fakeDnsRecordClient).handleCreateOrUpdate(inputRecord, physicalResourceIdBijection.to(physicalResourceId).some)
    }
  }

  test("CloudflareDnsRecordHandler delete should delete is successful even if the physical ID passed by CloudFormation doesn't exist") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val physicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")

    val fakeDnsRecordClient = stub(new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
        Stream.empty

      override def deleteDnsRecord(physicalResourceId: String): Stream[IO, PhysicalResourceId] =
        Stream.raiseError(DnsRecordIdDoesNotExistException("fake-url"))
    })

    for
      resp <- UpdateCloudflare(fakeDnsRecordClient).handleDelete(physicalResourceIdBijection.to(physicalResourceId))
      log <- summon[TestingLoggerFactory[IO]].logged
    yield
      assertEquals(resp.physicalId, physicalResourceId)
      assertEquals(resp.data, None)
      assertEquals(log, Vector(TestingLoggerFactory.Warn("com.dwolla.lambda.cloudflare.record.UpdateCloudflareSuite", """The record could not be deleted because it did not exist; nonetheless, responding with Success!""", None)))
  }

  test("CloudflareDnsRecordHandler delete should propagate exceptions thrown by the Cloudflare client when a delete fails") {
    given TestingLoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()
    given Logger[IO] = LoggerFactory[IO].getLogger
    given Trace[IO] = natchez.Trace.Implicits.noop

    val physicalResourceId: model.PhysicalResourceId = PhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")
    val inputRecord = UnidentifiedDnsRecord(
      name = "example.dwolla.com",
      content = "example.dwollalabs.com",
      recordType = "CNAME",
      ttl = Option(42),
      proxied = Option(true)
    )

    val fakeDnsRecordClient = stub(new DnsRecordClientStub(Stream.raiseError[IO](StubException)) {
      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
        Stream.emit(inputRecord.identifyAs(physicalResourceId)).unNone

      override def deleteDnsRecord(physicalResourceId: String): Stream[IO, PhysicalResourceId] =
        Stream.raiseError(NoStackTraceException)
    })

    interceptIO[NoStackTraceException.type] {
      UpdateCloudflare(fakeDnsRecordClient).handleDelete(physicalResourceIdBijection.to(physicalResourceId))
    }
  }

  test("DnsRecordTypeChange should mention the existing and new record types") {
    interceptMessage("""Refusing to change DNS record from "existing" to "new".""") {
      throw DnsRecordTypeChange("existing", "new")
    }
  }

}

case object NoStackTraceException extends NoStackTrace {
  override def getMessage: String = "NoStackTraceException"
  override def toString: String = getMessage
}

case object StubException extends NoStackTrace
class EnhancedStubWithInstrumentation[F[_] : ApplicativeThrow] extends (Instrumentation[F, *] ~> F) {
  override def apply[A](fa: Instrumentation[F, A]): F[A] =
    fa.value.recoverWith {
      case StubException => new NotImplementedError(s"An implementation is missing for ${fa.algebraName}.${fa.methodName}").raiseError[F, A]
    }
}
