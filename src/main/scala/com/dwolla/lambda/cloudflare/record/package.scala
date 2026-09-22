package com.dwolla.lambda.cloudflare

import cats.syntax.all.*
import com.dwolla.cloudflare.domain.model
import com.dwolla.cloudflare.domain.model.UnidentifiedDnsRecord
import feral.lambda.cloudformation
import io.circe.*
import smithy4s.Bijection

package object record {
  given physicalResourceIdBijection: Bijection[model.PhysicalResourceId, cloudformation.PhysicalResourceId] =
    Bijection[model.PhysicalResourceId, cloudformation.PhysicalResourceId](
      model.PhysicalResourceId.codec.extract.map(cloudformation.PhysicalResourceId.unsafeApply),
      cloudformation.PhysicalResourceId.codec.extract.map(model.PhysicalResourceId(_)),
    )

  given Decoder[UnidentifiedDnsRecord] = (c: HCursor) =>
    for {
      name <- c.downField("Name").as[String]
      content <- c.downField("Content").as[String]
      recordType <- c.downField("Type").as[String]
      ttl <- c.downField("TTL").as[Option[Int]]
      proxied <- c.downField("Proxied").as[Option[String]].map(_.flatMap(str => try { Some(str.toBoolean) } catch { case _: IllegalArgumentException => None }))
      priority <- c.downField("Priority").as[Option[Int]]
    } yield UnidentifiedDnsRecord(name, content, recordType, ttl, proxied, priority)
}
