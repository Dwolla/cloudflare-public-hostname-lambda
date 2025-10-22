package com.dwolla.lambda.cloudflare.record

import _root_.fs2.*
import _root_.io.circe.*
import _root_.io.circe.generic.auto.*
import _root_.io.circe.syntax.*
import _root_.io.circe.literal.*
import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.effect.*
import cats.effect.std.*
import cats.mtl.*
import com.amazonaws.kms.*
import com.amazonaws.kms.KMSGen.DecryptError
import com.dwolla.cloudflare.*
import com.dwolla.cloudflare.domain.model
import com.dwolla.cloudflare.domain.model.*
import com.dwolla.cloudflare.domain.dto.*
import com.dwolla.cloudflare.domain.dto.dns.*
import com.dwolla.cloudflare.domain.model.Exceptions.RecordAlreadyExists
import com.dwolla.lambda.cloudflare.record.DnsRecordTypeChange
import feral.lambda.*
import feral.lambda.cloudformation
import feral.lambda.cloudformation.{PhysicalResourceId as _, *}
import org.typelevel.scalaccompat.annotation.targetName3
import smithy4s.*
import smithy4s.kinds.*
import org.http4s.client.Client
import org.http4s.{BuildInfo as _, *}
import org.http4s.syntax.all.*
import org.http4s.dsl.*
import org.http4s.circe.*
import org.http4s.server.*
import org.http4s.server.middleware.authentication.challenged
import org.http4s.dsl.io.*
import munit.*
import natchez.*
import natchez.http4s.*
import natchez.mtl.*
import natchez.mtl.http4s.syntax.entrypoint.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.console.ConsoleLoggerFactory
import _root_.scodec.bits.*
import CloudflareQueryParams.*
import com.dwolla.tracing.LowPriorityTraceableValueInstances.*

import scala.concurrent.duration.*

class ResponseCapturingHttpApp[F[_] : Async](queue: Queue[F, Either[Json, CloudFormationCustomResourceResponse]]) {
  private val dsl: Http4sDsl[F] = Http4sDsl[F]
  import dsl.*

  private given [A: Decoder, B: Decoder]: Decoder[Either[A, B]] =
    Decoder[B].map(_.asRight) or Decoder[A].map(_.asLeft)

  private given [A: Decoder]: EntityDecoder[F, A] = jsonOf

  def responses: QueueSource[F, Either[Json, CloudFormationCustomResourceResponse]] = queue

  def routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req@PUT -> Root / "cloudformation-response" =>
      for
        json <- req.as[Either[Json, CloudFormationCustomResourceResponse]]
        _ <- queue.offer(json)
        resp <- Ok()
      yield resp
  }

  def client: Client[F] = Client.fromHttpApp(routes.orNotFound)

  val uri: Uri = uri"/cloudformation-response"
}

object ResponseCapturingHttpApp {
  def apply[F[_] : Async]: F[ResponseCapturingHttpApp[F]] =
    Queue.unbounded[F, Either[Json, CloudFormationCustomResourceResponse]].map(new ResponseCapturingHttpApp(_))
}

object CloudflareQueryParams {
  object domainNameQueryParamMatcher extends QueryParamDecoderMatcher[String]("name")
  object statusQueryParamMatcher extends QueryParamDecoderMatcher[String]("status")
  object typeQueryParamMatcher extends QueryParamDecoderMatcher[String]("type")

}

object CloudflareAuth {
  type CloudflareAuthenticator[F[_], A] = CloudflareAuthorization => F[Option[A]]

  private def validate[F[_]](expected: CloudflareAuthorization): CloudflareAuthenticator[F, CloudflareAuthorization] =
    Option(_).filter(_ == expected).pure[F]

  def apply[F[_]: Sync, A]: AuthMiddleware[F, A] =
    challenged(challenge("Cloudflare", validate))

  def challenge[F[_]: Applicative](expected: CloudflareAuthorization): Kleisli[F, Request[F], Either[Challenge, AuthedRequest[F, CloudflareAuthorization]]] =
    Kleisli { req =>
      validatePassword(validate, req).map {
        case Some(authInfo) =>
          Right(AuthedRequest(authInfo, req))
        case None =>
          Left(Challenge("Cloudflare", realm, authParams))
      }
    }

  private def validatePassword[F[_] : Applicative](expected: CloudflareAuthorization,
                                                   req: Request[F]): F[Option[CloudflareAuthorization]] =
    (req.headers.get[`X-Auth-Email`], req.headers.get[`X-Auth-Key`]) match {
      case Some((Some(email), Some(key))) if email == expected.email && key == expected.key =>
        expected.some.pure[F]
      case _ =>
        none.pure[F]
    }

}

@annotation.experimental
class UpdateCloudflareSpec extends CatsEffectSuite {

  given [F[_] : Sync]: LoggerFactory[F] = ConsoleLoggerFactory.create

  private val tagPhysicalResourceId: String => model.PhysicalResourceId = PhysicalResourceId(_)
  private val tagZoneId: String => model.ZoneId = ZoneId(_)
  private val tagResourceId: String => model.ResourceId = ResourceId(_)

  given Show[InMemory.Lineage] with
    def show(lineage: InMemory.Lineage): String = lineage match
      case InMemory.Lineage.Root(name) => name
      case InMemory.Lineage.Child(parent, name) => s"${parent.show}/${name.show}"

  given Show[Kernel] with
    def show(kernel: Kernel): String = kernel.toHeaders.map { case (k, v) => s"$k: $v" }.mkString(", ")

  given Show[Span.Options] with
    def show(options: Span.Options): String = options.toString

  given Show[List[(String, TraceValue)]] with
    def show(fields: List[(String, TraceValue)]): String = fields.map {
      case (k, TraceValue.StringValue(v)) => s"$k: $v"
      case (k, TraceValue.BooleanValue(v)) => s"$k: $v"
      case (k, TraceValue.NumberValue(v)) => s"$k: $v"
    }
      .mkString(", ")

  given Show[InMemory.NatchezCommand] with
    def show(command: InMemory.NatchezCommand): String = command match
      case InMemory.NatchezCommand.AskKernel(kernel) => s"AskKernel(${kernel.show})"
      case InMemory.NatchezCommand.AskSpanId => "AskSpanId"
      case InMemory.NatchezCommand.AskTraceId => "AskTraceId"
      case InMemory.NatchezCommand.AskTraceUri => "AskTraceUri"
      case InMemory.NatchezCommand.Put(fields) => s"Put(${fields.show})"
      case InMemory.NatchezCommand.CreateSpan(name, kernel, options) => s"CreateSpan($name, ${kernel.show}, ${options.show})"
      case InMemory.NatchezCommand.ReleaseSpan(name) => s"ReleaseSpan($name)"
      case InMemory.NatchezCommand.AttachError(err, fields) => s"AttachError(${Option(err.getMessage).getOrElse(err.toString)} (${err.getStackTrace.headOption.map(_.toString).getOrElse("no stack trace")}), ${fields.show})"
      case InMemory.NatchezCommand.LogEvent(event) => s"LogEvent($event)"
      case InMemory.NatchezCommand.LogFields(fields) => s"LogFields(${fields.show})"
      case InMemory.NatchezCommand.CreateRootSpan(name, kernel, options) => s"CreateRootSpan($name, ${kernel.show}, ${options.show})"
      case InMemory.NatchezCommand.ReleaseRootSpan(name) => s"ReleaseRootSpan($name)"

  /**
   * This object provides functionality to generate an identifier from a given name
   * and to extract the original name from a given identifier.
   *
   * The `apply` method is responsible for converting a string input (`name`)
   * into its hexadecimal representation. This serves as a form of identifier or key.
   *
   * The `unapply` method performs the reverse operation, taking a hexadecimal identifier
   * and decoding it back into its original string, if possible.
   */
  private object idFromName {
    def apply(name: String): String =
      BitVector
        .encodeUtf8(name)
        .map(_.toHex)
        .toOption
        .get

    def unapply(id: String): Option[String] =
      BitVector
        .fromHex(id)
        .flatMap(_.decodeUtf8.toOption)
  }

  test("CloudflareDnsRecordHandler should propagate exceptions thrown by the KMS decrypter") {
    for
      given Local[IO, Span[IO]] <- IO.local(Span.noop[IO])
      entryPoint <- InMemory.EntryPoint.create[IO]
      fakeCloudFormation <- ResponseCapturingHttpApp[IO]
      kmsExceptionMessage = "The ciphertext refers to a customer master key that does not exist, does not exist in this region, or you are not allowed to access"
      mockKms = new KMSGen.Constant[Kind1[IO]#toKind5](IO.raiseError(KeyUnavailableException(ErrorMessageType(kmsExceptionMessage).some)))
      handler = CloudflareDnsRecordHandler.buildHandler(entryPoint, fakeCloudFormation.client, mockKms)
      request <- buildRequest(
        requestType = CloudFormationRequestType.UpdateRequest,
        physicalResourceId = cloudformation.PhysicalResourceId("different-physical-id"),
        responseUri = fakeCloudFormation.uri,
        resourceProperties = Map(
          "Name" -> Json.fromString("example.dwolla.com"),
          "Content" -> Json.fromString("new-example.dwollalabs.com"),
          "Type" -> Json.fromString("CNAME"),
          "TTL" -> Json.fromString("42"),
          "Proxied" -> Json.fromString("true"),
          "CloudflareEmail" -> Json.fromString(utf8Bytes"cloudflare-account-email@dwollalabs.com".toBase64),
          "CloudflareKey" -> Json.fromString(utf8Bytes"fake-key".toBase64)
        ).some,
      )
      output <- handler(request)
      _ <- entryPoint.ref.get.map(_.mkString_("\n")).flatMap(IO.println)
      response <- fakeCloudFormation.responses.take
    yield {
      assertEquals(output, None)
      assertEquals(response, CloudFormationCustomResourceResponse(
        Status = RequestResponseStatus.Failed,
        Reason = kmsExceptionMessage.some,
        PhysicalResourceId = cloudformation.PhysicalResourceId("different-physical-id"),
        StackId = StackId(""),
        RequestId = RequestId(""),
        LogicalResourceId = LogicalResourceId(""),
        Data =
          json"""{
             "StackTrace": [
               "com.amazonaws.kms.KeyUnavailableException: The ciphertext refers to a customer master key that does not exist, does not exist in this region, or you are not allowed to access"
             ]
           }""",
      ).asRight)
    }
  }

  test("UpdateCloudflare create should create specified CNAME record") {
    for
      given Local[IO, Span[IO]] <- IO.local(Span.noop[IO])
      entryPoint <- InMemory.EntryPoint.create[IO]
      fakeCloudFormation <- ResponseCapturingHttpApp[IO]
      cloudflare = HttpRoutes.of[IO] {
        case req@GET -> Root / "client" / "v4" / "zones" :? domainNameQueryParamMatcher(name) +& statusQueryParamMatcher("active") =>
          Ok(ResponseDTO(
            success = true,
            result = ZoneDTO(id = idFromName(name).some, name = name),
            errors = None,
            messages = None,
          ).asJson)

        case GET -> Root / "client" / "v4" / "zones" / idFromName(_) / "dns_records" :? domainNameQueryParamMatcher(_) +& typeQueryParamMatcher("CNAME") =>
            Ok(PagedResponseDTO(
              result = List.empty[DnsRecordDTO],
              success = true,
              errors = None,
              messages = None,
              result_info = ResultInfoDTO(
                page = 1,
                per_page = 10,
                count = 1,
                total_pages = 1,
                total_count = 1,
              ).some
            ).asJson)

        case req@POST -> Root / "client" / "v4" / "zones" / idFromName("dwolla.com") / "dns_records" =>
          given [A: Decoder]: EntityDecoder[IO, A] = jsonOf[IO, A]

          for
            dto <- req.as[DnsRecordDTO]
            _ <- Trace[IO].put("received" -> dto)
            resp <- Ok(ResponseDTO(
              result = dto.copy(id = idFromName(dto.name).some).some,
              success = true,
              errors = None,
              messages = None,
            ).asJson)
          yield resp
      }

      client = NatchezMiddleware.client(Client.fromHttpApp(entryPoint.liftRoutes((cloudflare) <+> fakeCloudFormation.routes).orNotFound))

      mockKms = new KMSGen.Constant[Kind1[IO]#toKind5](IO.stub) {
        override def decrypt(ciphertextBlob: CiphertextType,
                             encryptionContext: Option[Map[EncryptionContextKey, EncryptionContextValue]],
                             grantTokens: Option[List[GrantTokenType]],
                             keyId: Option[KeyIdType],
                             encryptionAlgorithm: Option[EncryptionAlgorithmSpec],
                             recipient: Option[RecipientInfo],
                             dryRun: Option[NullableBooleanType]): IO[DecryptResponse] =
          DecryptResponse(plaintext = PlaintextType(ciphertextBlob.value).some).pure[IO]
      }

      handler = CloudflareDnsRecordHandler.buildHandler(entryPoint, client, mockKms)
      request <- buildRequest(
        requestType = CloudFormationRequestType.CreateRequest,
        physicalResourceId = None,
        responseUri = fakeCloudFormation.uri,
        resourceProperties = Map(
          "Name" -> Json.fromString("example.dwolla.com"),
          "Content" -> Json.fromString("example.dwollalabs.com"),
          "Type" -> Json.fromString("CNAME"),
          "TTL" -> Json.fromString("42"),
          "Proxied" -> Json.fromString("true"),
          "CloudflareEmail" -> Json.fromString(utf8Bytes"cloudflare-account-email@dwollalabs.com".toBase64),
          "CloudflareKey" -> Json.fromString(utf8Bytes"fake-key".toBase64)
        ).some,
      )
      output <- handler(request)
      captured <- fakeCloudFormation.responses.take
    yield
      val expectedPhysicalResourceId = s"https://api.cloudflare.com/client/v4/zones/${idFromName("dwolla.com")}/dns_records/${idFromName("example.dwolla.com")}"
      val expected =
        json"""{
              "physicalResourceId" : $expectedPhysicalResourceId,
              "zoneId" : ${idFromName("dwolla.com")},
              "resourceId" : ${idFromName("example.dwolla.com")},
              "name" : "example.dwolla.com",
              "content" : "example.dwollalabs.com",
              "recordType" : "CNAME",
              "ttl" : 42,
              "proxied" : true
            }"""
      assertEquals(output, None)
      assertEquals(captured, CloudFormationCustomResourceResponse(
        Status = RequestResponseStatus.Success,
        Reason = None,
        PhysicalResourceId = cloudformation.PhysicalResourceId(expectedPhysicalResourceId),
        StackId = StackId(""),
        RequestId = RequestId(""),
        LogicalResourceId = LogicalResourceId(""),
        Data =
          json"""{
                "dnsRecord": $expected,
                "created": $expected
              }""",
      ).asRight)
  }

//  test("UpdateCloudflare create should log failure and close the clients if creation fails") {
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//
//    val fakeCloudflareClient = new FakeDnsRecordClient {
//      override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
//        if (record == inputRecord) Stream.raiseError(NoStackTraceException)
//        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))
//
//      override def getExistingDnsRecords(name: String,
//                                         content: Option[String],
//                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
//        if (name == "example.dwolla.com" && recordType.contains("CNAME")) Stream.empty
//        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, None)
//
//    output.attempt.compile.toList.map(_.head).unsafeToFuture() must beLeft[Throwable](NoStackTraceException).await
//  }
//
//  test("UpdateCloudflare create should propagate exception if fetching existing records fails") {
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//
//    val fakeCloudflareClient = new FakeDnsRecordClient {
//      override def getExistingDnsRecords(name: String,
//                                         content: Option[String],
//                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
//        Stream.raiseError(NoStackTraceException)
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, None)
//
//    output.attempt.compile.toList.map(_.head).unsafeToFuture() must beLeft[Throwable](NoStackTraceException).await
//  }
//
//  test("UpdateCloudflare create should create a CNAME record if it doesn't exist, despite having a physical ID provided by CloudFormation") {
//    val providedPhysicalId = Option("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id")
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//    val expectedRecord = IdentifiedDnsRecord(
//      physicalResourceId = tagPhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"),
//      zoneId = tagZoneId("fake-zone-id"),
//      resourceId = tagResourceId("fake-resource-id"),
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//
//    val fakeCloudflareClient: FakeDnsRecordClient = new FakeDnsRecordClient {
//      override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
//        if (record == inputRecord) Stream.emit(expectedRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))
//
//      override def getExistingDnsRecords(name: String,
//                                         content: Option[String],
//                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
//        if (name == "example.dwolla.com" && recordType.contains("CNAME")) Stream.empty
//        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, providedPhysicalId)
//
//    output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
//      case List(handlerResponse) =>
//        handlerResponse.physicalId must_== expectedRecord.physicalResourceId
//        handlerResponse.data must havePair("dnsRecord" -> expectedRecord.asJson)
//        handlerResponse.data must havePair("oldDnsRecord" -> None.asJson)
//    }.await
//  }
//
//  test("UpdateCloudflare create should create a DNS record that isn't an CNAME even if record(s) with the same name already exist") {
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "MX",
//      ttl = Option(42),
//      proxied = Option(true),
//      priority = Option(10),
//    )
//    val expectedRecord = IdentifiedDnsRecord(
//      physicalResourceId = tagPhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"),
//      zoneId = tagZoneId("fake-zone-id"),
//      resourceId = tagResourceId("fake-resource-id"),
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "MX",
//      ttl = Option(42),
//      proxied = Option(true),
//      priority = Option(10),
//    )
//
//    val fakeCloudflareClient: FakeDnsRecordClient = new FakeDnsRecordClient {
//      override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
//        if (record == inputRecord) Stream.emit(expectedRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))
//
//      override def getExistingDnsRecords(name: String,
//                                         content: Option[String],
//                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
//        Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, None)
//
//    output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
//      case List(handlerResponse) =>
//        handlerResponse.physicalId must_== "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//        handlerResponse.data must havePair("dnsRecord" -> expectedRecord.asJson)
//        handlerResponse.data must havePair("created" -> expectedRecord.asJson)
//        handlerResponse.data must havePair("updated" -> None.asJson)
//        handlerResponse.data must havePair("oldDnsRecord" -> None.asJson)
//    }.await
//  }
//
//  test("UpdateCloudflare create should pretend to have created a DNS record that isn't an CNAME if Cloudflare complains that the record already exists") {
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "MX",
//      ttl = Option(42),
//      proxied = Option(true),
//      priority = Option(10),
//    )
//    val expectedRecord = IdentifiedDnsRecord(
//      physicalResourceId = tagPhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"),
//      zoneId = tagZoneId("fake-zone-id"),
//      resourceId = tagResourceId("fake-resource-id"),
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "MX",
//      ttl = Option(42),
//      proxied = Option(true),
//      priority = Option(10),
//    )
//    val existingRecord = expectedRecord.copy(
//      physicalResourceId = tagPhysicalResourceId("https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/different-record"),
//      resourceId = tagResourceId("different-record"),
//      content = "different-content",
//      priority = Option(0),
//    )
//
//    val fakeCloudflareClient = new FakeDnsRecordClient {
//      override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
//        if (record == inputRecord) Stream.raiseError(RecordAlreadyExists)
//        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))
//
//      override def getExistingDnsRecords(name: String,
//                                         content: Option[String],
//                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
//        if (name == existingRecord.name) Stream.emit(existingRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, None)
//
//    output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
//      case List(handlerResponse) =>
//        handlerResponse.physicalId must_== existingRecord.physicalResourceId
//        handlerResponse.data must havePair("dnsRecord" -> existingRecord.asJson)
//        handlerResponse.data must havePair("created" -> existingRecord.asJson)
//        handlerResponse.data must havePair("updated" -> None.asJson)
//        handlerResponse.data must havePair("oldDnsRecord" -> None.asJson)
//    }.await
//  }
//
//  test("CloudflareDnsRecordHandler update should update a non-CNAME DNS record if it already exists, if its physical ID is passed in by CloudFormation") {
//    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "MX",
//      ttl = Option(42),
//      proxied = Option(true),
//      priority = Option(10),
//    )
//    val existingRecord = IdentifiedDnsRecord(
//      physicalResourceId = tagPhysicalResourceId(physicalResourceId),
//      zoneId = tagZoneId("fake-zone-id"),
//      resourceId = tagResourceId("fake-resource-id"),
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "MX",
//      ttl = Option(42),
//      proxied = None,
//      priority = Option(10),
//    )
//
//    val expectedRecord = existingRecord.copy(content = "new-example.dwollalabs.com")
//
//    val fakeCloudflareClient = new FakeDnsRecordClient {
//      override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
//        if (record == inputRecord.identifyAs(physicalResourceId)) Stream.emit(expectedRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))
//
//      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
//        if (physicalResourceId == existingRecord.physicalResourceId) Stream.emit(existingRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($physicalResourceId)"))
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))
//
//    output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
//      case List(handlerResponse) =>
//        handlerResponse.physicalId must_== expectedRecord.physicalResourceId
//        handlerResponse.data must havePair("dnsRecord" -> expectedRecord.asJson)
//        handlerResponse.data must havePair("oldDnsRecord" -> existingRecord.asJson)
//    }.await
//
//    // TODO deal with logging
////      there were noCallsTo(mockLogger)
//  }
//
//  test("CloudflareDnsRecordHandler update should update a CNAME DNS record if it already exists, even if no physical ID is passed in by CloudFormation") {
//    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true),
//    )
//    val existingRecord = IdentifiedDnsRecord(
//      physicalResourceId = tagPhysicalResourceId(physicalResourceId),
//      zoneId = tagZoneId("fake-zone-id"),
//      resourceId = tagResourceId("fake-resource-id"),
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//
//    val expectedRecord = existingRecord.copy(content = "new-example.dwollalabs.com")
//
//    val fakeCloudflareClient = new FakeDnsRecordClient {
//      override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
//        if (record == inputRecord.identifyAs(physicalResourceId)) Stream.emit(expectedRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))
//
//      override def getExistingDnsRecords(name: String,
//                                         content: Option[String],
//                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
//        if (name == existingRecord.name) Stream.emit(existingRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("CrEaTe", inputRecord, Option(physicalResourceId))
//
//    output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
//      case List(handlerResponse) =>
//        handlerResponse.physicalId must_== expectedRecord.physicalResourceId
//        handlerResponse.data must havePair("dnsRecord" -> expectedRecord.asJson)
//        handlerResponse.data must havePair("oldDnsRecord" -> existingRecord.asJson)
//    }.await
//
////      there was one(mockLogger).warn(startsWith("""Discovered DNS record ID "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id" for hostname "example.dwolla.com""""))
//  }
//
//  test("CloudflareDnsRecordHandler update should update a CNAME DNS record if it already exists, even if the physical ID passed in by CloudFormation doesn't match the existing ID (returning the new ID)") {
//    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//    val existingRecord = IdentifiedDnsRecord(
//      physicalResourceId = tagPhysicalResourceId(physicalResourceId),
//      zoneId = tagZoneId("fake-zone-id"),
//      resourceId = tagResourceId("fake-resource-id"),
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//    val expectedRecord = existingRecord.copy(content = "new-example.dwollalabs.com")
//    val inputRecord = existingRecord.unidentify
//
//    val fakeCloudflareClient = new FakeDnsRecordClient {
//      override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] =
//        if (record == inputRecord.identifyAs(physicalResourceId)) Stream.emit(expectedRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected argument: $record"))
//
//      override def getExistingDnsRecords(name: String,
//                                         content: Option[String],
//                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
//        if (name == existingRecord.name) Stream.emit(existingRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))
//
//    output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
//      case List(handlerResponse) =>
//        handlerResponse.physicalId must_== expectedRecord.physicalResourceId
//        handlerResponse.data must havePair("dnsRecord" -> expectedRecord.asJson)
//        handlerResponse.data must havePair("oldDnsRecord" -> existingRecord.asJson)
//    }.await
//
//    // TODO deal with logging
////      there was one(mockLogger).warn(startsWith(
////        """The passed physical ID "different-physical-id" does not match the discovered physical ID "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id" for hostname "example.dwolla.com"."""))
//  }
//
//  test("CloudflareDnsRecordHandler update should refuse to change the record type if the input type is CNAME") {
//    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//
//    val existingRecord = IdentifiedDnsRecord(
//      physicalResourceId = tagPhysicalResourceId(physicalResourceId),
//      zoneId = tagZoneId("fake-zone-id"),
//      resourceId = tagResourceId("fake-resource-id"),
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "A",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//    val inputRecord = existingRecord.unidentify.copy(content = "new-example.dwollalabs.com", recordType = "CNAME")
//
//    val fakeCloudflareClient = new FakeDnsRecordClient {
//      override def getExistingDnsRecords(name: String,
//                                         content: Option[String],
//                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
//        if (name == existingRecord.name && recordType.contains("CNAME")) Stream.emit(existingRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))
//
//    output.attempt.compile.toList.map(_.head).unsafeRunSync() must beLeft[Throwable].like {
//      case DnsRecordTypeChange(existingRecordType, newRecordType) =>
//        existingRecordType must_== "A"
//        newRecordType must_== "CNAME"
//    }
//  }
//
//  test("CloudflareDnsRecordHandler update should refuse to change the record type if the input type is not CNAME") {
//    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//
//    val existingRecord = IdentifiedDnsRecord(
//      physicalResourceId = tagPhysicalResourceId(physicalResourceId),
//      zoneId = tagZoneId("fake-zone-id"),
//      resourceId = tagResourceId("fake-resource-id"),
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "MX",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//    val inputRecord = existingRecord.unidentify.copy(content = "new text", recordType = "TXT")
//
//    val fakeCloudflareClient: FakeDnsRecordClient = new FakeDnsRecordClient {
//      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
//        Stream.emit(existingRecord)
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))
//
//    output.attempt.compile.toList.map(_.head).unsafeRunSync() must beLeft[Throwable].like {
//      case DnsRecordTypeChange(existingRecordType, newRecordType) =>
//        existingRecordType must_== "MX"
//        newRecordType must_== "TXT"
//    }
//  }
//
//  test("CloudflareDnsRecordHandler update should propagate the failure exception if update fails") {
//    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//    val existingRecord = IdentifiedDnsRecord(
//      physicalResourceId = tagPhysicalResourceId(physicalResourceId),
//      zoneId = tagZoneId("fake-zone-id"),
//      resourceId = tagResourceId("fake-resource-id"),
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//
//    val inputRecord = existingRecord.unidentify.copy(content = "new-example.dwolla.com")
//
//    val fakeCloudflareClient: FakeDnsRecordClient = new FakeDnsRecordClient {
//      override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError(NoStackTraceException)
//
//      override def getExistingDnsRecords(name: String,
//                                         content: Option[String],
//                                         recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] =
//        if (name == existingRecord.name && recordType.contains(existingRecord.recordType)) Stream.emit(existingRecord)
//        else Stream.raiseError(new RuntimeException(s"unexpected arguments: ($name, $content, $recordType)"))
//    }
//
//    val output = UpdateCloudflare(fakeCloudflareClient)("update", inputRecord, Option(physicalResourceId))
//
//    output.attempt.compile.toList.map(_.head).unsafeRunSync() must beLeft[Throwable](NoStackTraceException)
//  }
//
//  test("CloudflareDnsRecordHandler delete should delete a DNS record if requested") {
//    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//    val existingRecord = inputRecord.identifyAs(physicalResourceId)
//
//    val fakeDnsRecordClient: FakeDnsRecordClient = new FakeDnsRecordClient {
//      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
//        Stream.emit(existingRecord)
//
//      override def deleteDnsRecord(physicalResourceId: String): Stream[IO, PhysicalResourceId] =
//        Stream.emit(physicalResourceId).map(tagPhysicalResourceId)
//    }
//
//    val output = UpdateCloudflare(fakeDnsRecordClient)("delete", inputRecord, Option(physicalResourceId))
//
//    output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
//      case List(handlerResponse) =>
//        handlerResponse.physicalId must_== physicalResourceId
//        handlerResponse.data must havePair("deletedRecordId" -> physicalResourceId.asJson)
//    }.await
//  }
//
//  test("CloudflareDnsRecordHandler delete should delete is successful even if the physical ID passed by CloudFormation doesn't exist") {
//    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//
//    val fakeDnsRecordClient: FakeDnsRecordClient = new FakeDnsRecordClient {
//      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
//        Stream.empty
//
//      override def deleteDnsRecord(physicalResourceId: String): Stream[IO, PhysicalResourceId] =
//        Stream.raiseError(DnsRecordIdDoesNotExistException("fake-url"))
//    }
//
//    val output = UpdateCloudflare(fakeDnsRecordClient)("delete", inputRecord, Option(physicalResourceId))
//
//    output.compile.toList.unsafeToFuture() must beLike[List[HandlerResponse]] {
//      case List(handlerResponse) =>
//        handlerResponse.physicalId must_== physicalResourceId
//        handlerResponse.data must not(havePair("deletedRecordId" -> physicalResourceId))
//    }.await
//
//    // TODO deal with logging
////      there was one(mockLogger).error("The record could not be deleted because it did not exist; nonetheless, responding with Success!",
////        DnsRecordIdDoesNotExistException("fake-url"))
//  }
//
//  test("CloudflareDnsRecordHandler delete should log failure and close the clients if delete fails") {
//    val physicalResourceId = "https://api.cloudflare.com/client/v4/zones/fake-zone-id/dns_records/fake-resource-id"
//    val inputRecord = UnidentifiedDnsRecord(
//      name = "example.dwolla.com",
//      content = "example.dwollalabs.com",
//      recordType = "CNAME",
//      ttl = Option(42),
//      proxied = Option(true)
//    )
//
//    val fakeDnsRecordClient: FakeDnsRecordClient = new FakeDnsRecordClient {
//      override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] =
//        Stream.emit(inputRecord.identifyAs(physicalResourceId))
//
//      override def deleteDnsRecord(physicalResourceId: String): Stream[IO, PhysicalResourceId] =
//        Stream.raiseError(NoStackTraceException)
//    }
//
//    val output = UpdateCloudflare(fakeDnsRecordClient)("delete", inputRecord, Option(physicalResourceId))
//
//    output.attempt.compile.toList.map(_.head).unsafeRunSync() must beLeft[Throwable](NoStackTraceException)
//  }
//
//  test("DnsRecordTypeChange should mention the existing and new record types") {
//    DnsRecordTypeChange("existing", "new") must beLikeA[RuntimeException] {
//      case ex => ex.getMessage must_== """Refusing to change DNS record from "existing" to "new"."""
//    }
//  }

  private def buildRequest(requestType: CloudFormationRequestType,
                           physicalResourceId: Option[cloudformation.PhysicalResourceId],
                           resourceProperties: Option[Map[String, Json]],
                           responseUri: Uri,
                          ): IO[Invocation[IO, CloudFormationCustomResourceRequest[DnsRecordWithCredentials]]] = {
    json"""{
      "RequestType": $requestType,
      "ResponseURL": $responseUri,
      "StackId": "",
      "RequestId": "",
      "ResourceType": "",
      "LogicalResourceId": "",
      "PhysicalResourceId": $physicalResourceId,
      "ResourceProperties": $resourceProperties,
      "OldResourceProperties": null
    }"""
      .as[CloudFormationCustomResourceRequest[DnsRecordWithCredentials]]
      .map(Invocation.pure(_, Context(
        functionName = "CloudflareDnsRecordHandler",
        functionVersion = BuildInfo.version,
        invokedFunctionArn = "",
        memoryLimitInMB = 512, // TODO get from buildinfo
        awsRequestId = "",
        logGroupName = "",
        logStreamName = "",
        identity = None,
        clientContext = None,
        remainingTime = 60.seconds.pure[IO]
      )))
      .liftTo[IO]
  }

}

case class CustomNoStackTraceException(msg: String, ex: Throwable = null) extends RuntimeException(msg, ex, true, false)
