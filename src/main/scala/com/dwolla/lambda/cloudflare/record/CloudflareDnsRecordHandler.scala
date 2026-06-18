package com.dwolla.lambda.cloudflare.record

import _root_.io.circe.*
import _root_.io.circe.JsoniterScalaCodec.*
import _root_.io.circe.syntax.*
import cats.*
import cats.data.*
import cats.effect.std.Env
import cats.effect.{Trace as _, *}
import cats.mtl.Local
import cats.syntax.all.*
import cats.tagless.Derive
import cats.tagless.aop.*
import com.amazonaws.kms.{CiphertextType, KMS, PlaintextType}
import com.dwolla.cloudflare.*
import com.dwolla.cloudflare.domain.model
import com.dwolla.cloudflare.domain.model.*
import com.dwolla.cloudflare.domain.model.Exceptions.RecordAlreadyExists
import com.dwolla.lambda.cloudflare.record.NothingEncoder.*
import com.dwolla.tracing.LowPriorityTraceableValueInstances.*
import com.dwolla.tracing.syntax.*
import feral.lambda.cloudformation.{CloudFormationCustomResource, CloudFormationCustomResourceRequest, HandlerResponse}
import feral.lambda.*
import fs2.Stream
import fs2.io.compression.*
import fs2.io.net.Network
import mouse.all.*
import natchez.*
import natchez.http4s.*
import natchez.mtl.*
import natchez.xray.XRay
import org.http4s.Headers
import org.http4s.client.{Client, middleware}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.*
import org.typelevel.log4cats.console.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import smithy4s.aws.kernel.AwsRegion
import smithy4s.aws.{AwsClient, AwsEnvironment}
import smithy4s.json.Json.*

import scala.util.control.NoStackTrace

case class DnsRecordWithCredentials(dnsRecord: UnidentifiedDnsRecord,
                                    cloudflareEmail: CiphertextType,
                                    cloudflareKey: CiphertextType,
                                   )

object DnsRecordWithCredentials {
  implicit val decoder: Decoder[DnsRecordWithCredentials] = Decoder[UnidentifiedDnsRecord].flatMap { dnsRecord =>
    (Decoder[CiphertextType].at("CloudflareEmail"), Decoder[CiphertextType].at("CloudflareKey"))
      .mapN(DnsRecordWithCredentials(dnsRecord, _, _))
  }

  implicit val encoder: Encoder[DnsRecordWithCredentials] = Encoder.instance { recordWithCredentials =>
    recordWithCredentials.asJson.mapObject {
      _
        .add("CloudflareEmail", recordWithCredentials.cloudflareEmail.asJson)
        .add("CloudflareKey", recordWithCredentials.cloudflareKey.asJson)
    }
  }
}

case class NoPlaintextForCiphertext(ciphertext: CiphertextType)
  extends RuntimeException(s"KMS returned no plaintext for ciphertext input $ciphertext")
    with NoStackTrace

@annotation.experimental
class CloudflareDnsRecordHandler[F[_] : {Concurrent, LoggerFactory, NonEmptyParallel, Trace}](httpClient: Client[F],
                                                                                              kms: KMS[F],
                                                                                              dnsRecordClient: StreamingCloudflareApiExecutor[F] => DnsRecordClient[Stream[F, *]],
                                                                                              ) extends CloudFormationCustomResource[F, DnsRecordWithCredentials, JsonObject] {
  private implicit val logger: Logger[F] = LoggerFactory[F].getLogger

  private def constructCloudflareClient(input: DnsRecordWithCredentials): F[DnsRecordClient[Stream[F, *]]] =
    for {
      (email, key) <- decryptSensitiveProperties(input)
      executor = new StreamingCloudflareApiExecutor[F](httpClient, CloudflareAuthorization(email.value.toUTF8String, key.value.toUTF8String))
    } yield dnsRecordClient(executor)

  private def decrypt(ciphertext: CiphertextType): F[PlaintextType] =
    for {
      res <- kms.decrypt(ciphertext)
      out <- res.plaintext.toRight(NoPlaintextForCiphertext(ciphertext)).liftTo[F]
    } yield out

  private def decryptSensitiveProperties(input: DnsRecordWithCredentials): F[(PlaintextType, PlaintextType)] =
    (decrypt(input.cloudflareEmail), decrypt(input.cloudflareKey)).parTupled

  override def createResource(input: DnsRecordWithCredentials): F[HandlerResponse[JsonObject]] =
    constructCloudflareClient(input)
      .map(UpdateCloudflare(_))
      .flatMap(_.handleCreateOrUpdate(input.dnsRecord, None))

  override def updateResource(input: DnsRecordWithCredentials, physicalResourceId: cloudformation.PhysicalResourceId): F[HandlerResponse[JsonObject]] =
    constructCloudflareClient(input)
      .map(UpdateCloudflare(_))
      .flatMap(_.handleCreateOrUpdate(input.dnsRecord, physicalResourceId.some))

  override def deleteResource(input: DnsRecordWithCredentials, physicalResourceId: cloudformation.PhysicalResourceId): F[HandlerResponse[JsonObject]] =
    constructCloudflareClient(input)
      .map(UpdateCloudflare(_))
      .flatMap(_.handleDelete(physicalResourceId))

}

object NothingEncoder {
  @annotation.nowarn("msg=dead code following this construct")
  implicit val encoder: Encoder[Nothing] = Encoder.instance[Nothing](_ => Json.Null)
}

object CloudflareDnsRecordHandler extends IOLambda[CloudFormationCustomResourceRequest[DnsRecordWithCredentials], Nothing] {
  private def httpClient[F[_] : {Async, Network, Trace}]: Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .build
      .map(middleware.Logger(logHeaders = true, logBody = true, (Headers.SensitiveHeaders + ci"X-Auth-Key").contains))
      .map(NatchezMiddleware.client(_))

  override def handler: Resource[IO, Invocation[IO, CloudFormationCustomResourceRequest[DnsRecordWithCredentials]] => IO[Option[Nothing]]] =
    for
      xray <- XRay.entryPoint[IO]()
      given LoggerFactory[IO] = ConsoleLoggerFactory.create[IO]
      given Local[IO, Span[IO]] <- IO.local(Span.noop[IO]).toResource
      client <- httpClient[IO]
      region <- Env[IO].get("AWS_REGION").liftEitherT(new RuntimeException("missing AWS_REGION environment variable")).map(AwsRegion(_)).rethrowT.toResource
      awsEnv <- AwsEnvironment.default(client, region)
      kms <- AwsClient(KMS, awsEnv)
    yield buildHandler(xray, client, kms)(DnsRecordClient(_))

  def buildHandler[F[_] : {Concurrent, LoggerFactory, NonEmptyParallel}](entryPoint: EntryPoint[F],
                                                                         client: Client[F],
                                                                         kms: KMS[F])
                                                                        (dnsRecordClient: StreamingCloudflareApiExecutor[F] => DnsRecordClient[Stream[F, *]])
                                                                        (using Local[F, Span[F]]): Invocation[F, CloudFormationCustomResourceRequest[DnsRecordWithCredentials]] => F[Option[Nothing]] =
    implicit inv =>
      given KernelSource[CloudFormationCustomResourceRequest[DnsRecordWithCredentials]] = KernelSource.emptyKernelSource

      TracedHandler(
        entryPoint,
        Kleisli { (span: Span[F]) =>
          summon[Local[F, Span[F]]].scope {
            CloudFormationCustomResource(client, new CloudflareDnsRecordHandler(client, kms, dnsRecordClient))
          }(span)
        }
      )

}

trait UpdateCloudflare[F[_]] {
  def handleCreateOrUpdate(unidentifiedDnsRecord: UnidentifiedDnsRecord,
                           cloudformationProvidedPhysicalResourceId: Option[cloudformation.PhysicalResourceId]): F[HandlerResponse[JsonObject]]

  def handleDelete(physicalResourceId: cloudformation.PhysicalResourceId): F[HandlerResponse[JsonObject]]
}

@annotation.experimental
object UpdateCloudflare {
  implicit val physicalResourceIdTraceableValue: TraceableValue[cloudformation.PhysicalResourceId] = TraceableValue[String].contramap(_.value)
  implicit val aspect: Aspect[UpdateCloudflare, TraceableValue, TraceableValue] = Derive.aspect

  def apply[F[_] : {Concurrent, Logger, Trace}](cloudflare: DnsRecordClient[Stream[F, *]]): UpdateCloudflare[F] =
    (new UpdateCloudflareImpl(cloudflare): UpdateCloudflare[F]).traceWithInputsAndOutputs
}

class UpdateCloudflareImpl[F[_] : {Concurrent, Logger, Trace}](cloudflare: DnsRecordClient[Stream[F, *]]) extends UpdateCloudflare[F] {

  def handleCreateOrUpdate(unidentifiedDnsRecord: UnidentifiedDnsRecord,
                           cloudformationProvidedPhysicalResourceId: Option[cloudformation.PhysicalResourceId]): F[HandlerResponse[JsonObject]] = {
    unidentifiedDnsRecord.recordType.toUpperCase() match {
      case "CNAME" => handleCreateOrUpdateCNAME(unidentifiedDnsRecord, cloudformationProvidedPhysicalResourceId)
      case _ => handleCreateOrUpdateNonCNAME(unidentifiedDnsRecord, cloudformationProvidedPhysicalResourceId)
    }
  }

  def handleDelete(physicalResourceId: cloudformation.PhysicalResourceId): F[HandlerResponse[JsonObject]] =
    Trace[F].span("DnsRecordClient.getByUri >> DnsRecordClient.deleteDnsRecord") {
      Trace[F].put("physicalResourceId.input" -> physicalResourceId) >>
        cloudflare.getByUri(physicalResourceId.value)
          .map(_.physicalResourceId)
          .flatMap(id => Stream.eval(Trace[F].put("physicalResourceId.found" -> id)) >> cloudflare.deleteDnsRecord(id))
          .compile
          .toList
      }
      .flatMap {
        case Nil => warnAboutMissingRecordDeletion(physicalResourceId)
        case deleted :: Nil =>
          val data = JsonObject(
            "deletedRecordId" -> deleted.asJson
          )

          HandlerResponse(physicalResourceId, data.some).pure[F]

        case multipleDeleted =>
          val data = JsonObject(
            "deletedRecordIds" -> multipleDeleted.asJson
          )

          HandlerResponse(physicalResourceId, data.some).pure[F]
      }

  private def warnAboutMissingRecordDeletion(physicalResourceId: cloudformation.PhysicalResourceId): F[HandlerResponse[JsonObject]] =
    Logger[F].warn("The record could not be deleted because it did not exist; nonetheless, responding with Success!")
      .as(HandlerResponse(physicalResourceId, None))

  private def handleCreateOrUpdateNonCNAME(unidentifiedDnsRecord: UnidentifiedDnsRecord,
                                           cloudformationProvidedPhysicalResourceId: Option[cloudformation.PhysicalResourceId])
                                          : F[HandlerResponse[JsonObject]] =
    for {
      maybeExistingRecord <- cloudformationProvidedPhysicalResourceId.map(_.value).flatTraverse(str => Trace[F].span("DnsRecordClient.getByUri") {
        for {
          _ <- Trace[F].put("physicalResourceId.input" -> str)
          out <- cloudflare.getByUri(str).compile.last
          _ <- Trace[F].put("output" -> out)
        } yield out
      })
      createOrUpdate <- maybeExistingRecord.fold(createRecord)(updateRecord).run(unidentifiedDnsRecord)
    } yield createOrUpdateToHandlerResponse(createOrUpdate, maybeExistingRecord)

  private def findAtMostOneExistingCNAME(name: String): F[Option[IdentifiedDnsRecord]] =
    cloudflare.getExistingDnsRecords(name, recordType = Option("CNAME")).compile.toList
      .flatMap { identifiedDnsRecords =>
        if (identifiedDnsRecords.size < 2) identifiedDnsRecords.headOption.pure[F]
        else MultipleCloudflareRecordsExistForDomainNameException(name, identifiedDnsRecords.map {
          import com.dwolla.cloudflare.domain.model.Implicits.*
          _.toDto
        }.toSet).raiseError
      }

  private def handleCreateOrUpdateCNAME(unidentifiedDnsRecord: UnidentifiedDnsRecord,
                                        cloudformationProvidedPhysicalResourceId: Option[cloudformation.PhysicalResourceId]): F[HandlerResponse[JsonObject]] =
    for {
      maybeIdentifiedDnsRecord <- findAtMostOneExistingCNAME(unidentifiedDnsRecord.name)
      createOrUpdate <- maybeIdentifiedDnsRecord.fold(createRecord)(updateRecord).run(unidentifiedDnsRecord)
      _ <- warnIfProvidedIdDoesNotMatchDiscoveredId(cloudformationProvidedPhysicalResourceId, maybeIdentifiedDnsRecord, unidentifiedDnsRecord.name)
      _ <- warnIfNoIdWasProvidedButDnsRecordExisted(cloudformationProvidedPhysicalResourceId, maybeIdentifiedDnsRecord)
    } yield createOrUpdateToHandlerResponse(createOrUpdate, maybeIdentifiedDnsRecord)

  // TODO add tracing
  private def createRecord: Kleisli[F, UnidentifiedDnsRecord, CreateOrUpdate[IdentifiedDnsRecord]] =
    Kleisli { unidentifiedDnsRecord =>
      Trace[F].span("createRecord") {
        cloudflare
          .createDnsRecord(unidentifiedDnsRecord)
          .compile
          .last
          .recoverWith {
            case RecordAlreadyExists =>
              Trace[F].span("createRecord.RecordAlreadyExists") {
                cloudflare
                  .getExistingDnsRecords(unidentifiedDnsRecord.name, Option(unidentifiedDnsRecord.content), Option(unidentifiedDnsRecord.recordType))
                  .compile
                  .last
              }
          }
          .liftOptionT
          .getOrRaise(new NoSuchElementException(s"No DNS record was created for ${unidentifiedDnsRecord.name}"))
          .map(CreateOrUpdate.create)
      }
    }

  // TODO add tracing
  private def updateRecord(existingRecord: IdentifiedDnsRecord): Kleisli[F, UnidentifiedDnsRecord, CreateOrUpdate[IdentifiedDnsRecord]] =
    assertRecordTypeWillNotChange(existingRecord.recordType)
      .map(_.identifyAs(existingRecord.physicalResourceId.value))
      .andThen {
        Stream.emit(_)
          .unNone
          .flatMap {
            cloudflare
              .updateDnsRecord(_)
              .map(CreateOrUpdate.update)
          }
          .compile
          .lastOrError
      }

  private def warnIfProvidedIdDoesNotMatchDiscoveredId(physicalResourceId: Option[cloudformation.PhysicalResourceId],
                                                       updateableRecord: Option[IdentifiedDnsRecord],
                                                       hostname: String): F[Unit] = {
    val warning =
      for {
        providedId <- physicalResourceId
        discoveredId <- updateableRecord.map(_.physicalResourceId)
        if providedId.value != discoveredId.value
      } yield s"""The passed physical ID "$providedId" does not match the discovered physical ID "$discoveredId" for hostname "$hostname". This may indicate a change to this stack's DNS entries that was not managed by CloudFormation. Updating the discovered record instead of the record passed by CloudFormation."""

    warning.traverse_(Logger[F].warn(_))
  }

  private def warnIfNoIdWasProvidedButDnsRecordExisted(physicalResourceId: Option[cloudformation.PhysicalResourceId], existingRecord: Option[IdentifiedDnsRecord]): F[Unit] = {
    val warning =
      for {
        dnsRecord <- existingRecord
        discoveredId = dnsRecord.physicalResourceId
        if (physicalResourceId.isEmpty)
      } yield s"""Discovered DNS record ID "$discoveredId" for hostname "${dnsRecord.name}", with existing content "${dnsRecord.content}". This record will be updated instead of creating a new record."""

    warning.traverse_(Logger[F].warn(_))
  }

  private def createOrUpdateToHandlerResponse(createOrUpdate: CreateOrUpdate[IdentifiedDnsRecord], existingRecord: Option[IdentifiedDnsRecord]): HandlerResponse[JsonObject] = {
    val dnsRecord = createOrUpdate.value
    val data = JsonObject(
      "dnsRecord" -> dnsRecord.asJson,
      "created" -> createOrUpdate.create.asJson,
      "updated" -> createOrUpdate.update.asJson,
      "oldDnsRecord" -> existingRecord.asJson,
    )

    HandlerResponse(physicalResourceIdBijection.to(dnsRecord.physicalResourceId), data.some)
  }

  private def assertRecordTypeWillNotChange(existingRecordType: String): Kleisli[F, UnidentifiedDnsRecord, UnidentifiedDnsRecord] =
    Kleisli { unidentifiedDnsRecord =>
      if (unidentifiedDnsRecord.recordType == existingRecordType)
        unidentifiedDnsRecord.pure
      else
        DnsRecordTypeChange(existingRecordType, unidentifiedDnsRecord.recordType).raiseError
    }
}

case class DnsRecordTypeChange(existingRecordType: String, newRecordType: String)
  extends RuntimeException(s"""Refusing to change DNS record from "$existingRecordType" to "$newRecordType".""")
