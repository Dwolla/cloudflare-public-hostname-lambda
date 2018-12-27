package com.dwolla.lambda.cloudflare.record

import _root_.fs2._
import cats.effect._
import com.amazonaws.services.kms.model.AWSKMSException
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model._
import com.dwolla.lambda.cloudformation._
import com.dwolla.testutils.exceptions.NoStackTraceException
import _root_.io.circe._
import _root_.io.circe.generic.auto._
import _root_.io.circe.syntax._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import com.dwolla.fs2aws.kms._
import com.dwolla.lambda.cloudflare.record.UpdateCloudflare.DnsRecordTypeChange

class UpdateCloudflareSpec(implicit ee: ExecutionEnv) extends Specification with Mockito {

  private val tagPhysicalResourceId = shapeless.tag[PhysicalResourceIdTag][String] _
  private val tagZoneId = shapeless.tag[ZoneIdTag][String] _
  private val tagResourceId = shapeless.tag[ResourceIdTag][String] _

  "CloudflareDnsRecordHandler" should {
    "propagate exceptions thrown by the KMS decrypter" >> {
      val kmsExceptionMessage = "The ciphertext refers to a customer master key that does not exist, does not exist in this region, or you are not allowed to access"

      val mockKms = new ExceptionRaisingDecrypter[IO](new AWSKMSException(kmsExceptionMessage))
      val handler = new CloudflareDnsRecordHandler(Stream.empty, Stream.emit(mockKms))

      val request = buildRequest(
        requestType = "update",
        physicalResourceId = Option("different-physical-id"),
        resourceProperties = Option(Map(
          "Name" → Json.fromString("example.dwolla.com"),
          "Content" → Json.fromString("new-example.dwollalabs.com"),
          "Type" → Json.fromString("CNAME"),
          "TTL" → Json.fromString("42"),
          "Proxied" → Json.fromString("true"),
          "CloudflareEmail" → Json.fromString("cloudflare-account-email@dwollalabs.com"),
          "CloudflareKey" → Json.fromString("fake-key")
        ))
      )

      val output = handler.handleRequest(request).unsafeToFuture()

      output must throwA[AWSKMSException].like { case ex ⇒ ex.getMessage must startWith(kmsExceptionMessage) }.await
    }
  }

  "UpdateCloudflare create" should {

    "create specified CNAME record" >> {
      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )
      val expectedRecord = IdentifiedDnsRecord(
        physicalResourceId = tagPhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"),
        zoneId = tagZoneId("fake-zone-id"),
        resourceId = tagResourceId("fake-resource-id"),
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
          if (record == inputRecord) Stream.emit(expectedRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

        override def getExistingDnsRecords(name: String,
                                           content: Option[String],
                                           recordType: Option[String]) =
          if (name == "example.dwolla.com" && recordType.contains("CNAME")) Stream.empty
          else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, None)

      output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
        case List(handlerResponse) ⇒
          handlerResponse.physicalId must_== "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
          handlerResponse.data must havePair("dnsRecord" → expectedRecord.asJson)
          handlerResponse.data must havePair("created" → expectedRecord.asJson)
          handlerResponse.data must havePair("updated" → None.asJson)
          handlerResponse.data must havePair("oldDnsRecord" → None.asJson)
      }.await
    }

    "log failure and close the clients if creation fails" >> {
      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
          if (record == inputRecord) Stream.raiseError(NoStackTraceException)
          else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

        override def getExistingDnsRecords(name: String,
                                           content: Option[String],
                                           recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
          if (name == "example.dwolla.com" && recordType.contains("CNAME")) Stream.empty
          else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, None)

      output.attempt.compile.toList.map(_.head).unsafeToFuture() must beLeft[Throwable](NoStackTraceException).await
    }

    "propagate exception if fetching existing records fails" >> {
      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def getExistingDnsRecords(name: String,
                                           content: Option[String],
                                           recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
          Stream.raiseError(NoStackTraceException)
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, None)

      output.attempt.compile.toList.map(_.head).unsafeToFuture() must beLeft[Throwable](NoStackTraceException).await
    }

    "create a CNAME record if it doesn't exist, despite having a physical ID provided by CloudFormation" >> {
      val providedPhysicalId = Option("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")
      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )
      val expectedRecord = IdentifiedDnsRecord(
        physicalResourceId = tagPhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"),
        zoneId = tagZoneId("fake-zone-id"),
        resourceId = tagResourceId("fake-resource-id"),
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
          if (record == inputRecord) Stream.emit(expectedRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

        override def getExistingDnsRecords(name: String,
                                           content: Option[String],
                                           recordType: Option[String]) =
          if (name == "example.dwolla.com" && recordType.contains("CNAME")) Stream.empty
          else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, providedPhysicalId)

      output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
        case List(handlerResponse) ⇒
          handlerResponse.physicalId must_== expectedRecord.physicalResourceId
          handlerResponse.data must havePair("dnsRecord" → expectedRecord.asJson)
          handlerResponse.data must havePair("oldDnsRecord" → None.asJson)
      }.await
    }

    "create a DNS record that isn't an CNAME even if record(s) with the same name already exist" >> {
      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "MX",
        ttl = Option(42),
        proxied = Option(true),
        priority = Option(10),
      )
      val expectedRecord = IdentifiedDnsRecord(
        physicalResourceId = tagPhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"),
        zoneId = tagZoneId("fake-zone-id"),
        resourceId = tagResourceId("fake-resource-id"),
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "MX",
        ttl = Option(42),
        proxied = Option(true),
        priority = Option(10),
      )

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
          if (record == inputRecord) Stream.emit(expectedRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

        override def getExistingDnsRecords(name: String,
                                           content: Option[String],
                                           recordType: Option[String]) =
          Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, None)

      output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
        case List(handlerResponse) ⇒
          handlerResponse.physicalId must_== "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
          handlerResponse.data must havePair("dnsRecord" → expectedRecord.asJson)
          handlerResponse.data must havePair("created" → expectedRecord.asJson)
          handlerResponse.data must havePair("updated" → None.asJson)
          handlerResponse.data must havePair("oldDnsRecord" → None.asJson)
      }.await
    }
  }

  "CloudflareDnsRecordHandler update" should {
    "update a non-CNAME DNS record if it already exists, if its physical ID is passed in by CloudFormation" >> {
      val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"

      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "MX",
        ttl = Option(42),
        proxied = Option(true),
        priority = Option(10),
      )
      val existingRecord = IdentifiedDnsRecord(
        physicalResourceId = tagPhysicalResourceId(physicalResourceId),
        zoneId = tagZoneId("fake-zone-id"),
        resourceId = tagResourceId("fake-resource-id"),
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "MX",
        ttl = Option(42),
        proxied = None,
        priority = Option(10),
      )

      val expectedRecord = existingRecord.copy(content = "new-example.dwollalabs.com")

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def updateDnsRecord(record: IdentifiedDnsRecord) =
          if (record == inputRecord.identifyAs(physicalResourceId)) Stream.emit(expectedRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

        override def getByUri(uri: String) =
          if (physicalResourceId == existingRecord.physicalResourceId) Stream.emit(existingRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($physicalResourceId)"))
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))

      output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
        case List(handlerResponse) ⇒
          handlerResponse.physicalId must_== expectedRecord.physicalResourceId
          handlerResponse.data must havePair("dnsRecord" → expectedRecord.asJson)
          handlerResponse.data must havePair("oldDnsRecord" → existingRecord.asJson)
      }.await

      // TODO deal with logging
//      there were noCallsTo(mockLogger)
    }

    "update a CNAME DNS record if it already exists, even if no physical ID is passed in by CloudFormation" >> {
      val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true),
      )
      val existingRecord = IdentifiedDnsRecord(
        physicalResourceId = tagPhysicalResourceId(physicalResourceId),
        zoneId = tagZoneId("fake-zone-id"),
        resourceId = tagResourceId("fake-resource-id"),
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )

      val expectedRecord = existingRecord.copy(content = "new-example.dwollalabs.com")

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def updateDnsRecord(record: IdentifiedDnsRecord) =
          if (record == inputRecord.identifyAs(physicalResourceId)) Stream.emit(expectedRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

        override def getExistingDnsRecords(name: String,
                                           content: Option[String],
                                           recordType: Option[String]) =
          if (name == existingRecord.name) Stream.emit(existingRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, Option(physicalResourceId))

      output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
        case List(handlerResponse) ⇒
          handlerResponse.physicalId must_== expectedRecord.physicalResourceId
          handlerResponse.data must havePair("dnsRecord" → expectedRecord.asJson)
          handlerResponse.data must havePair("oldDnsRecord" → existingRecord.asJson)
      }.await

//      there was one(mockLogger).warn(startsWith("""Discovered DNS record ID "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id" for hostname "example.dwolla.com""""))
    }

    "update a CNAME DNS record if it already exists, even if the physical ID passed in by CloudFormation doesn't match the existing ID (returning the new ID)" >> {
      val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
      val existingRecord = IdentifiedDnsRecord(
        physicalResourceId = tagPhysicalResourceId(physicalResourceId),
        zoneId = tagZoneId("fake-zone-id"),
        resourceId = tagResourceId("fake-resource-id"),
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )
      val expectedRecord = existingRecord.copy(content = "new-example.dwollalabs.com")
      val inputRecord = existingRecord.unidentify

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def updateDnsRecord(record: IdentifiedDnsRecord) =
          if (record == inputRecord.identifyAs(physicalResourceId)) Stream.emit(expectedRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))

        override def getExistingDnsRecords(name: String,
                                           content: Option[String],
                                           recordType: Option[String]) =
          if (name == existingRecord.name) Stream.emit(existingRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))

      output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
        case List(handlerResponse) ⇒
          handlerResponse.physicalId must_== expectedRecord.physicalResourceId
          handlerResponse.data must havePair("dnsRecord" → expectedRecord.asJson)
          handlerResponse.data must havePair("oldDnsRecord" → existingRecord.asJson)
      }.await

      // TODO deal with logging
//      there was one(mockLogger).warn(startsWith(
//        """The passed physical ID "different-physical-id" does not match the discovered physical ID "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id" for hostname "example.dwolla.com"."""))
    }

    "refuse to change the record type if the input type is CNAME" >> {
      val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"

      val existingRecord = IdentifiedDnsRecord(
        physicalResourceId = tagPhysicalResourceId(physicalResourceId),
        zoneId = tagZoneId("fake-zone-id"),
        resourceId = tagResourceId("fake-resource-id"),
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "A",
        ttl = Option(42),
        proxied = Option(true)
      )
      val inputRecord = existingRecord.unidentify.copy(content = "new-example.dwollalabs.com", recordType = "CNAME")

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def getExistingDnsRecords(name: String,
                                           content: Option[String],
                                           recordType: Option[String]) =
          if (name == existingRecord.name && recordType.contains("CNAME")) Stream.emit(existingRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))

      output.attempt.compile.toList.map(_.head).unsafeRunSync() must beLeft[Throwable].like {
        case DnsRecordTypeChange(existingRecordType, newRecordType) ⇒
          existingRecordType must_== "A"
          newRecordType must_== "CNAME"
      }
    }

    "refuse to change the record type if the input type is not CNAME" >> {
      val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"

      val existingRecord = IdentifiedDnsRecord(
        physicalResourceId = tagPhysicalResourceId(physicalResourceId),
        zoneId = tagZoneId("fake-zone-id"),
        resourceId = tagResourceId("fake-resource-id"),
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "MX",
        ttl = Option(42),
        proxied = Option(true)
      )
      val inputRecord = existingRecord.unidentify.copy(content = "new text", recordType = "TXT")

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def getByUri(uri: String) =
          Stream.emit(existingRecord)
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))

      output.attempt.compile.toList.map(_.head).unsafeRunSync() must beLeft[Throwable].like {
        case DnsRecordTypeChange(existingRecordType, newRecordType) ⇒
          existingRecordType must_== "MX"
          newRecordType must_== "TXT"
      }
    }

    "propagate the failure exception if update fails" >> {
      val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
      val existingRecord = IdentifiedDnsRecord(
        physicalResourceId = tagPhysicalResourceId(physicalResourceId),
        zoneId = tagZoneId("fake-zone-id"),
        resourceId = tagResourceId("fake-resource-id"),
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )

      val inputRecord = existingRecord.unidentify.copy(content = "new-example.dwolla.com")

      val fakeCloudflareClient = new FakeDnsRecordClient {
        override def updateDnsRecord(record: IdentifiedDnsRecord) = Stream.raiseError(NoStackTraceException)

        override def getExistingDnsRecords(name: String,
                                           content: Option[String],
                                           recordType: Option[String]) =
          if (name == existingRecord.name && recordType.contains(existingRecord.recordType)) Stream.emit(existingRecord)
          else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
      }

      val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))

      output.attempt.compile.toList.map(_.head).unsafeRunSync() must beLeft[Throwable](NoStackTraceException)
    }
  }

  "CloudflareDnsRecordHandler delete" should {
    "delete a DNS record if requested" >> {
      val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )
      val existingRecord = inputRecord.identifyAs(physicalResourceId)

      val fakeDnsRecordClient = new FakeDnsRecordClient {
        override def getByUri(uri: String) =
          Stream.emit(existingRecord)

        override def deleteDnsRecord(physicalResourceId: String) =
          Stream.emit(physicalResourceId).map(tagPhysicalResourceId)
      }

      val output = UpdateCloudflare(fakeDnsRecordClient)("delete", inputRecord, Option(physicalResourceId))

      output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
        case List(handlerResponse) ⇒
          handlerResponse.physicalId must_== physicalResourceId
          handlerResponse.data must havePair("deletedRecordId" → physicalResourceId.asJson)
      }.await
    }

    "delete is successful even if the physical ID passed by CloudFormation doesn't exist" >> {
      val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )

      val fakeDnsRecordClient = new FakeDnsRecordClient {
        override def getByUri(uri: String) =
          Stream.empty

        override def deleteDnsRecord(physicalResourceId: String) =
          Stream.raiseError(DnsRecordIdDoesNotExistException("fake-url"))
      }

      val output = UpdateCloudflare(fakeDnsRecordClient)("delete", inputRecord, Option(physicalResourceId))

      output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
        case List(handlerResponse) ⇒
          handlerResponse.physicalId must_== physicalResourceId
          handlerResponse.data must not(havePair("deletedRecordId" → physicalResourceId))
      }.await

      // TODO deal with logging
//      there was one(mockLogger).error("The record could not be deleted because it did not exist; nonetheless, responding with Success!",
//        DnsRecordIdDoesNotExistException("fake-url"))
    }

    "log failure and close the clients if delete fails" >> {
      val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
      val inputRecord = UnidentifiedDnsRecord(
        name = "example.dwolla.com",
        content = "example.dwollalabs.com",
        recordType = "CNAME",
        ttl = Option(42),
        proxied = Option(true)
      )

      val fakeDnsRecordClient = new FakeDnsRecordClient {
        override def getByUri(uri: String) =
          Stream.emit(inputRecord.identifyAs(physicalResourceId))

        override def deleteDnsRecord(physicalResourceId: String) =
          Stream.raiseError(NoStackTraceException)
      }

      val output = UpdateCloudflare(fakeDnsRecordClient)("delete", inputRecord, Option(physicalResourceId))

      output.attempt.compile.toList.map(_.head).unsafeRunSync() must beLeft[Throwable](NoStackTraceException)
    }
  }

  "Exceptions" >> {
    "DnsRecordTypeChange" should {
      "mention the existing and new record types" >> {
        DnsRecordTypeChange("existing", "new") must beLikeA[RuntimeException] {
          case ex ⇒ ex.getMessage must_== """Refusing to change DNS record from "existing" to "new"."""
        }
      }
    }
  }

  private def buildRequest(requestType: String,
                           physicalResourceId: Option[String],
                           resourceProperties: Option[Map[String, Json]]) =
    CloudFormationCustomResourceRequest(
      RequestType = requestType,
      ResponseURL = "",
      StackId = "",
      RequestId = "",
      ResourceType = "",
      LogicalResourceId = "",
      PhysicalResourceId = physicalResourceId,
      ResourceProperties = resourceProperties,
      OldResourceProperties = None
    )

}

case class CustomNoStackTraceException(msg: String, ex: Throwable = null) extends RuntimeException(msg, ex, true, false)

abstract class FakeDnsRecordClient extends DnsRecordClient[IO] {
  override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError(new NotImplementedError())

  override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError(new NotImplementedError())

  override def getExistingDnsRecords(name: String,
                                     content: Option[String],
                                     recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError(new NotImplementedError())

  override def getById(zoneId: ZoneId, resourceId: ResourceId): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError(new NotImplementedError())

  override def deleteDnsRecord(physicalResourceId: String): Stream[IO, PhysicalResourceId] = Stream.raiseError(new NotImplementedError())
}
