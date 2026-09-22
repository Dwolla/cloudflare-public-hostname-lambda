package com.dwolla.lambda.cloudflare.record

import com.dwolla.cloudflare.*
import com.dwolla.cloudflare.domain.model.*
import org.typelevel.scalaccompat.annotation.targetName3

class DnsRecordClientStub[F[+_]](const: F[Nothing]) extends DnsRecordClient[F] {
  override def getById(zoneId: ZoneId, resourceId: ResourceId): F[IdentifiedDnsRecord] = const
  override def createDnsRecord(record: UnidentifiedDnsRecord): F[IdentifiedDnsRecord] = const
  override def updateDnsRecord(record: IdentifiedDnsRecord): F[IdentifiedDnsRecord] = const
  override def getExistingDnsRecords(name: String, content: Option[String], recordType: Option[String]): F[IdentifiedDnsRecord] = const
  override def deleteDnsRecord(physicalResourceId: String): F[PhysicalResourceId] = const
  @targetName3("deleteDnsRecordNewtype")
  final override def deleteDnsRecord(physicalResourceId: PhysicalResourceId): F[PhysicalResourceId] = deleteDnsRecord(physicalResourceId.value)
  override def getByUri(uri: String): F[IdentifiedDnsRecord] = const
}
