package com.dwolla.lambda.cloudflare

import com.dwolla.cloudflare.domain.model.UnidentifiedDnsRecord
import io.circe._

package object record {
  implicit val decodeUnidentifiedDnsRecord: Decoder[UnidentifiedDnsRecord] = (c: HCursor) ⇒
    for {
      name ← c.downField("Name").as[String]
      content ← c.downField("Content").as[String]
      recordType ← c.downField("Type").as[String]
      ttl ← c.downField("TTL").as[Option[Int]]
      proxied ← c.downField("Proxied").as[Option[String]].map(_.flatMap(str ⇒ try { Some(str.toBoolean) } catch { case _: IllegalArgumentException ⇒ None }))
      priority ← c.downField("Priority").as[Option[Int]]
    } yield UnidentifiedDnsRecord(name, content, recordType, ttl, proxied, priority)
}
