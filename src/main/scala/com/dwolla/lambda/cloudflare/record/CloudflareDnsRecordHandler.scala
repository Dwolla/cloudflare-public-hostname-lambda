package com.dwolla.lambda.cloudflare.record

import _root_.fs2._
import _root_.io.circe._
import _root_.io.circe.fs2._
import _root_.io.circe.generic.auto._
import _root_.io.circe.syntax._
import cats.data._
import cats.effect._
import cats.implicits._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model._
import com.dwolla.fs2aws.kms.KmsDecrypter
import com.dwolla.lambda.cloudflare.record.CloudflareDnsRecordHandler.parseRecordFrom
import com.dwolla.lambda.cloudformation._
import org.http4s.Headers
import org.http4s.client.Client
import org.http4s.client.blaze._
import org.http4s.client.middleware.Logger
import org.http4s.syntax.string._

import scala.concurrent.ExecutionContext.Implicits.global

class CloudflareDnsRecordHandler(httpClientStream: Stream[IO, Client[IO]], kmsClientStream: Stream[IO, KmsDecrypter[IO]]) extends CatsAbstractCustomResourceHandler[IO] {
  def this() = this(
    Http1Client.stream[IO]().map(Logger(logHeaders = true, logBody = true, (Headers.SensitiveHeaders + "X-Auth-Key".ci).contains)),
    KmsDecrypter.stream[IO]()
  )

  private def constructCloudflareClient(resourceProperties: Map[String, Json]): Stream[IO, DnsRecordClient[IO]] =
    for {
      (email, key) ← decryptSensitiveProperties(resourceProperties)
      httpClient ← httpClientStream
      executor = new StreamingCloudflareApiExecutor[IO](httpClient, CloudflareAuthorization(email, key))
    } yield DnsRecordClient(executor)

  private def decryptSensitiveProperties(resourceProperties: Map[String, Json]): Stream[IO, (String, String)] =
    for {
      kmsClient ← kmsClientStream
      emailCryptoText ← Stream.emit(resourceProperties("CloudflareEmail")).covary[IO].through(decoder[IO, String])
      keyCryptoText ← Stream.emit(resourceProperties("CloudflareKey")).covary[IO].through(decoder[IO, String])
      plaintextMap ← kmsClient.decryptBase64("CloudflareEmail" → emailCryptoText, "CloudflareKey" → keyCryptoText).map(_.mapValues(new String(_, "UTF-8")))
      emailPlaintext = plaintextMap("CloudflareEmail")
      keyPlaintext = plaintextMap("CloudflareKey")
    } yield (emailPlaintext, keyPlaintext)

  override def handleRequest(input: CloudFormationCustomResourceRequest): IO[HandlerResponse] =
    (for {
      resourceProperties ← Stream.eval(IO.fromEither(input.ResourceProperties.toRight(MissingResourceProperties)))
      dnsRecord ← parseRecordFrom(resourceProperties)
      cloudflareClient ← constructCloudflareClient(resourceProperties)
      res ← UpdateCloudflare(cloudflareClient)(input.RequestType, dnsRecord, input.PhysicalResourceId)
    } yield res).compile.toList.map(_.head)

}

object CloudflareDnsRecordHandler {
  implicit class ParseJsonAs(json: Json) {
    def parseAs[A](implicit d: Decoder[A]): IO[A] = IO.fromEither(json.as[A])
  }

  def parseRecordFrom(resourceProperties: Map[String, Json]): Stream[IO, UnidentifiedDnsRecord] =
    Stream.eval(Json.obj(resourceProperties.toSeq: _*).parseAs[UnidentifiedDnsRecord])
}

object UpdateCloudflare {
  private val logger = org.slf4j.LoggerFactory.getLogger("LambdaLogger")

  private implicit def optionToStream[F[_], A](o: Option[A]): Stream[F, A] = Stream.emits(o.toSeq)

  /* Emit the stream, or if it's empty, some alternate value

    ```scala
      yourStream.pull.uncons {
       case None => Pull.output1(alternateValue)
       case Some((hd, tl)) => Pull.output(hd) >> tl.pull.echo
      }.stream
    ```
   */

  def apply(cloudflareDnsRecordClient: DnsRecordClient[IO])
           (requestType: String,
            unidentifiedDnsRecord: UnidentifiedDnsRecord,
            physicalResourceId: Option[String]): Stream[IO, HandlerResponse] =
    requestType.toUpperCase match {
      case "CREATE" | "UPDATE" ⇒
        handleCreateOrUpdate(unidentifiedDnsRecord, physicalResourceId)(cloudflareDnsRecordClient)
      case "DELETE" ⇒ handleDelete(physicalResourceId.get)(cloudflareDnsRecordClient)
    }

  private def handleCreateOrUpdate(unidentifiedDnsRecord: UnidentifiedDnsRecord, cloudformationProvidedPhysicalResourceId: Option[String])
                                  (implicit cloudflare: DnsRecordClient[IO]): Stream[IO, HandlerResponse] =
    unidentifiedDnsRecord.recordType.toUpperCase() match {
      case "CNAME" ⇒ Stream.eval(handleCreateOrUpdateCNAME(unidentifiedDnsRecord, cloudformationProvidedPhysicalResourceId))
      case _ ⇒ handleCreateOrUpdateNonCNAME(unidentifiedDnsRecord, cloudformationProvidedPhysicalResourceId)
    }

  private def handleDelete(physicalResourceId: String)
                          (implicit cloudflare: DnsRecordClient[IO]): Stream[IO, HandlerResponse] = {
    for {
      existingRecord ← cloudflare.getExistingDnsRecord(physicalResourceId)
      deleted ← cloudflare.deleteDnsRecord(existingRecord.physicalResourceId)
    } yield {
      val data = Map(
        "deletedRecordId" → Json.fromString(deleted)
      )

      HandlerResponse(physicalResourceId, data)
    }
  }.last.evalMap(_.fold(warnAboutMissingRecordDeletion(physicalResourceId))(_.pure[IO]))

  private def warnAboutMissingRecordDeletion(physicalResourceId: String): IO[HandlerResponse] =
    for {
      _ ← IO(logger.warn("The record could not be deleted because it did not exist; nonetheless, responding with Success!"))
    } yield HandlerResponse(physicalResourceId, Map.empty[String, Json])

  private def handleCreateOrUpdateNonCNAME(unidentifiedDnsRecord: UnidentifiedDnsRecord, cloudformationProvidedPhysicalResourceId: Stream[IO, String])
                                          (implicit cloudflare: DnsRecordClient[IO]): Stream[IO, HandlerResponse] = {
    for {
      maybeExistingRecord ← cloudformationProvidedPhysicalResourceId.flatMap(cloudflare.getExistingDnsRecord).last
      createOrUpdate ← maybeExistingRecord.fold(createRecord)(updateRecord).run(unidentifiedDnsRecord)
    } yield createOrUpdateToHandlerResponse(createOrUpdate, maybeExistingRecord)
  }

  private def findAtMostOneExistingCNAME(name: String)
                                        (implicit cloudflare: DnsRecordClient[IO]): IO[Option[IdentifiedDnsRecord]] =
    cloudflare.getExistingDnsRecords(name, recordType = Option("CNAME")).compile.toList
      .flatMap { identifiedDnsRecords ⇒
        if (identifiedDnsRecords.size < 2) IO.pure(identifiedDnsRecords.headOption)
        else IO.raiseError(MultipleCloudflareRecordsExistForDomainNameException(name, identifiedDnsRecords.map {
          import com.dwolla.cloudflare.domain.model.Implicits._
          _.toDto
        }.toSet))
      }

  private def handleCreateOrUpdateCNAME(unidentifiedDnsRecord: UnidentifiedDnsRecord, cloudformationProvidedPhysicalResourceId: Option[String])
                                       (implicit cloudflare: DnsRecordClient[IO]): IO[HandlerResponse] =
    for {
      maybeIdentifiedDnsRecord ← findAtMostOneExistingCNAME(unidentifiedDnsRecord.name)
      createOrUpdate ← maybeIdentifiedDnsRecord.fold(createRecord)(updateRecord).run(unidentifiedDnsRecord).compile.toList.map(_.head)
      _ ← warnIfProvidedIdDoesNotMatchDiscoveredId(cloudformationProvidedPhysicalResourceId, maybeIdentifiedDnsRecord, unidentifiedDnsRecord.name)
      _ ← warnIfNoIdWasProvidedButDnsRecordExisted(cloudformationProvidedPhysicalResourceId, maybeIdentifiedDnsRecord)
    } yield createOrUpdateToHandlerResponse(createOrUpdate, maybeIdentifiedDnsRecord)

  private def createRecord(implicit cloudflare: DnsRecordClient[IO]): Kleisli[Stream[IO, ?], UnidentifiedDnsRecord, CreateOrUpdate[IdentifiedDnsRecord]] =
    for {
      identifiedRecord ← Kleisli[Stream[IO, ?], UnidentifiedDnsRecord, IdentifiedDnsRecord](unidentifiedDnsRecord ⇒ cloudflare.createDnsRecord(unidentifiedDnsRecord))
    } yield Create(identifiedRecord)

  private def updateRecord(existingRecord: IdentifiedDnsRecord)(implicit cloudflare: DnsRecordClient[IO]): Kleisli[Stream[IO, ?], UnidentifiedDnsRecord, CreateOrUpdate[IdentifiedDnsRecord]] =
    for {
      update ← assertRecordTypeWillNotChange(existingRecord.recordType).andThen { unidentifiedDnsRecord ⇒
        cloudflare.updateDnsRecord(unidentifiedDnsRecord.identifyAs(existingRecord.physicalResourceId)).map(Update(_))
      }
    } yield update

  private def warnIfProvidedIdDoesNotMatchDiscoveredId(physicalResourceId: Option[String], updateableRecord: Option[IdentifiedDnsRecord], hostname: String): IO[Unit] = IO {
    for {
      providedId ← physicalResourceId
      discoveredId ← updateableRecord.map(_.physicalResourceId)
      if providedId != discoveredId
    } logger.warn(s"""The passed physical ID "$providedId" does not match the discovered physical ID "$discoveredId" for hostname "$hostname". This may indicate a change to this stack's DNS entries that was not managed by CloudFormation. Updating the discovered record instead of the record passed by CloudFormation.""")
  }

  private def warnIfNoIdWasProvidedButDnsRecordExisted(physicalResourceId: Option[String], existingRecord: Option[IdentifiedDnsRecord]): IO[Unit] = IO {
    if (physicalResourceId.isEmpty)
      for {
        dnsRecord ← existingRecord
        discoveredId ← dnsRecord.physicalResourceId
      } logger.warn(s"""Discovered DNS record ID "$discoveredId" for hostname "${dnsRecord.name}", with existing content "${dnsRecord.content}". This record will be updated instead of creating a new record.""")
  }

  private def createOrUpdateToHandlerResponse(createOrUpdate: CreateOrUpdate[IdentifiedDnsRecord], existingRecord: Option[IdentifiedDnsRecord]): HandlerResponse = {
    val dnsRecord = createOrUpdate.value
    val data = Map(
      "dnsRecord" → dnsRecord.asJson,
      "created" → createOrUpdate.create.asJson,
      "updated" → createOrUpdate.update.asJson,
      "oldDnsRecord" → existingRecord.asJson,
    )

    HandlerResponse(dnsRecord.physicalResourceId, data)
  }

  /*_*/
  private def assertRecordTypeWillNotChange(existingRecordType: String): Kleisli[Stream[IO, ?], UnidentifiedDnsRecord, UnidentifiedDnsRecord] =
    Kleisli(unidentifiedDnsRecord ⇒
      if (unidentifiedDnsRecord.recordType == existingRecordType)
        Stream.emit(unidentifiedDnsRecord)
      else
        Stream.raiseError(DnsRecordTypeChange(existingRecordType, unidentifiedDnsRecord.recordType)))
  /*_*/

  case class DnsRecordTypeChange(existingRecordType: String, newRecordType: String)
    extends RuntimeException(s"""Refusing to change DNS record from "$existingRecordType" to "$newRecordType".""")
}
