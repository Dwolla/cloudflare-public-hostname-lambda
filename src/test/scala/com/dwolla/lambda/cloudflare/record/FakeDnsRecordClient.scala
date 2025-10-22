package com.dwolla.lambda.cloudflare.record

import fs2.*
import cats.effect.*
import com.dwolla.cloudflare.*
import com.dwolla.cloudflare.domain.model.*
import org.typelevel.scalaccompat.annotation.targetName3

abstract class FakeDnsRecordClient extends DnsRecordClient[Stream[IO, *]] {
  override def createDnsRecord(record: UnidentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError[IO](new NotImplementedError())

  override def updateDnsRecord(record: IdentifiedDnsRecord): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError[IO](new NotImplementedError())

  override def getExistingDnsRecords(name: String,
                                     content: Option[String],
                                     recordType: Option[String]): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError[IO](new NotImplementedError())

  override def getById(zoneId: ZoneId, resourceId: ResourceId): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError[IO](new NotImplementedError())

  override def deleteDnsRecord(physicalResourceId: String): Stream[IO, PhysicalResourceId] = Stream.raiseError[IO](new NotImplementedError())

  @targetName3("deleteDnsRecordNewtype")
  final override def deleteDnsRecord(physicalResourceId: PhysicalResourceId): Stream[IO, PhysicalResourceId] = deleteDnsRecord(physicalResourceId.value)

  override def getByUri(uri: String): Stream[IO, IdentifiedDnsRecord] = Stream.raiseError[IO](new NotImplementedError())
}
