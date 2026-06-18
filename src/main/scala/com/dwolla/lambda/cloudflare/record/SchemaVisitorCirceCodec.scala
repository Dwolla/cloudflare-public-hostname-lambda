package com.dwolla.lambda.cloudflare.record

import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import smithy.api.TimestampFormat
import smithy4s.*
import smithy4s.Document.{DArray, DBoolean, DNull, DNumber, DObject, DString}
import smithy4s.schema.{Schema, *}

import java.util.Base64
import scala.util.Try

object SchemaVisitorCirceCodec extends CachedSchemaCompiler.Impl[Codec] {
  override protected type Aux[A] = Codec[A]

  override def fromSchema[A](schema: Schema[A], cache: CompilationCache[Codec]): Codec[A] =
    schema.compile(new SchemaVisitorCirceCodec(cache))
}

class SchemaVisitorCirceCodec(override protected val cache: CompilationCache[Codec]) extends SchemaVisitor.Cached[Codec] {
  self =>

  private implicit val blobEncoder: Encoder[Blob] = Encoder.encodeString.contramap(_.toBase64String)
  private implicit val blobDecoder: Decoder[Blob] = Decoder[String].emapTry { base64String =>
    Try(Base64.getDecoder.decode(base64String)).map(Blob(_))
  }

  private implicit def documentEncoder: Encoder[Document] = Encoder.instance {
    case DNumber(value) => value.asJson
    case DString(value) => value.asJson
    case DBoolean(value) => value.asJson
    case DNull => io.circe.Json.Null
    case DArray(value) => io.circe.Json.fromValues(value.map(_.asJson(using documentEncoder)))
    case DObject(value) => io.circe.Json.fromFields(value.map { case (k, v) => k -> v.asJson(using documentEncoder) })
  }
  private implicit def documentDecoder: Decoder[Document] = Decoder.instance { c =>
    c.value.foldWith(DocumentFolder).toRight(DecodingFailure("Could not decode document", c.history))
  }

  private implicit val timestampEncoder: Encoder[Timestamp] = Encoder[String].contramap(_.format(TimestampFormat.DATE_TIME))
  private implicit val timestampDecoder: Decoder[Timestamp] = Decoder[String].emap(Timestamp.parse(_, TimestampFormat.DATE_TIME).toRight(s"Could not parse timestamp; expected ${Timestamp.showFormat(TimestampFormat.DATE_TIME)}"))

  override def primitive[P](shapeId: ShapeId,
                            hints: Hints, 
                            tag: Primitive[P]): Codec[P] = {
    val enc: Encoder[P] = Primitive.deriving[Encoder].apply(tag)
    val dec: Decoder[P] = Primitive.deriving[Decoder].apply(tag)
    Codec.from(dec, enc)
  }

  override def collection[C[_], A](shapeId: ShapeId, hints: Hints, tag: CollectionTag[C], member: Schema[A]): Codec[C[A]] = {
    implicit val schemaA: Schema[A] = member

    val encoder: Encoder[C[A]] = Encoder.instance { (ca: C[A]) =>
      Document.array(tag.iterator(ca).map(Document.encode(_)).to(Iterable)).asJson
//      Document.array(ca.map(Document.encode(_)).toIterable).asJson
    }

    val decoder: Decoder[C[A]] = Decoder.instance { cursor =>
      cursor
        .values
        .toRight(DecodingFailure("Could not decode document", cursor.history))
        .flatMap(_.toList.traverse {
          _.foldWith(DocumentFolder)
            .toRight(DecodingFailure("Could not decode document", cursor.history))
            .flatMap(_.decode[A].leftMap(DecodingFailure.fromThrowable(_, cursor.history)))
        })
        .map(_.iterator)
        .map(tag.fromIterator)
    }

    Codec.from(decoder, encoder)
  }

  override def map[K, V](shapeId: ShapeId, hints: Hints, key: Schema[K], value: Schema[V]): Codec[Map[K, V]] = {
    implicit val keySchema: Schema[K] = key
    val keyEncoder: KeyEncoder[K] = KeyEncoder.instance { (k: K) =>
      Document.encode(k) match {
        case DString(s) => s
        case other      => other.toString
      }
    }
    val keyDecoder: KeyDecoder[K] = KeyDecoder.instance { (s: String) =>
      DString(s).decode[K].toOption
    }
    implicit val valueCodec: Codec[V] = SchemaVisitorCirceCodec.fromSchema(value, cache)

    val encoder: Encoder[Map[K, V]] = Encoder.instance { (map: Map[K, V]) =>
      io.circe.Json.fromFields(map.toList.map { case (k, v) =>
        keyEncoder(k) -> v.asJson
      })
    }

    val decoder: Decoder[Map[K, V]] = Decoder.instance { cursor =>
      cursor.keys
        .toRight(DecodingFailure("not an object", cursor.history))
        .map(_.toList)
        .flatMap {
          _.traverse { (key: String) =>
            for {
              k <- keyDecoder(key).toRight(DecodingFailure(s"Could not decode key $key", cursor.history))
              v <- cursor.downField(key).as[V]
            } yield k -> v
          }
        }
        .map(_.toMap)
    }

    Codec.from(decoder, encoder)
  }

  override def enumeration[E](shapeId: ShapeId, hints: Hints, tag: EnumTag[E], values: List[EnumValue[E]], total: E => EnumValue[E]): Codec[E] = {
    val encoder: Encoder[E] = Encoder.instance(e => io.circe.Json.fromString(total(e).stringValue))
    val decoder: Decoder[E] = Decoder.instance { cursor =>
      cursor.as[String].flatMap { str =>
        values
          .find(_.stringValue == str)
          .map(_.value)
          .toRight(DecodingFailure(s"Invalid enumeration value: $str", cursor.history))
      }
    }
    Codec.from(decoder, encoder)
  }

  override def struct[S](shapeId: ShapeId, hints: Hints, fields: Vector[Field[S, ?]], make: IndexedSeq[Any] => S): Codec[S] = {
    final case class EncF[A](field: Field[S, A], enc: Encoder[A], get: S => A)
    final case class DecF[A](field: Field[S, A], dec: Decoder[A])

    val encFields: Vector[EncF[?]] = fields.map { f0 =>
      val f = f0.asInstanceOf[Field[S, Any]]
      val codec = SchemaVisitorCirceCodec.fromSchema(f.schema, cache)
      EncF(f, codec.asInstanceOf[Encoder[Any]], f.get(_))
    }

    val decFields: Vector[DecF[?]] = fields.map { f0 =>
      val schAny = f0.schema
      val decAny = SchemaVisitorCirceCodec.fromSchema(schAny, cache).asInstanceOf[Decoder[Any]]
      DecF(f0.asInstanceOf[Field[S, Any]], decAny)
    }

    val encoder: Encoder[S] = Encoder.instance { (s: S) =>
      val kvs: Iterable[(String, Json)] = encFields.iterator.flatMap { ef =>
        val label = ef.field.label
        val v: Any = ef.get(s)
        v match {
          case opt: Option[_] => opt.map(o => label -> ef.enc.asInstanceOf[Encoder[Any]].apply(o))
          case other          => Some(label -> ef.enc.asInstanceOf[Encoder[Any]].apply(other))
        }
      }.toList
      io.circe.Json.obj(kvs.toSeq *)
    }

    val decoder: Decoder[S] = Decoder.instance { cursor =>
      decFields.zipWithIndex.toList
        .traverse { case (df, _) =>
          val label = df.field.label
          val sub = cursor.downField(label)
          sub.as(using df.dec)
        }
        .map(vec => make(vec.toIndexedSeq))
    }

    Codec.from(decoder, encoder)
  }

  override def union[U](shapeId: ShapeId, hints: Hints, alternatives: Vector[Alt[U, ?]], dispatch: Alt.Dispatcher[U]): Codec[U] = {
    final case class AltInfo[A](alt: Alt[U, A], enc: Encoder[A], dec: Decoder[A])

    val alts: Vector[AltInfo[?]] = alternatives.map { a0 =>
      val a = a0.asInstanceOf[Alt[U, Any]]
      val codec = SchemaVisitorCirceCodec.fromSchema(a.schema, cache)
      AltInfo(a0.asInstanceOf[Alt[U, Any]], codec.asInstanceOf[Encoder[Any]], codec.asInstanceOf[Decoder[Any]])
    }

    val encoder: Encoder[U] = Encoder.instance { (u: U) =>
      val fieldOpt: Option[(String, Json)] = alts.iterator.flatMap { ai =>
        val a = ai.alt.asInstanceOf[Alt[U, Any]]
        a.project.lift(u).map { v => a.label -> ai.enc.asInstanceOf[Encoder[Any]].apply(v) }
      }.toSeq.headOption
      fieldOpt match {
        case Some((label, json)) => Json.obj(label -> json)
        case None => Json.obj()
      }
    }

    val decoder: Decoder[U] = Decoder.instance { c =>
      c.value.asObject.toRight(DecodingFailure("not an object", c.history)).flatMap { obj =>
        obj.toMap.toList match {
          case (label, _) :: Nil =>
            alternatives.find(_.label == label).toRight(DecodingFailure(s"Unknown union alternative: $label", c.history)).flatMap { a0 =>
              val a = a0.asInstanceOf[Alt[U, Any]]
              val decAny = SchemaVisitorCirceCodec.fromSchema(a.schema, cache).asInstanceOf[Decoder[Any]]
              c.downField(label).as(using decAny).map(v => a.inject(v))
            }
          case Nil => Left(DecodingFailure("empty object for union", c.history))
          case _   => Left(DecodingFailure("expected single-field object for union", c.history))
        }
      }
    }

    Codec.from(decoder, encoder)
  }

  override def biject[A, B](schema: Schema[A], bijection: Bijection[A, B]): Codec[B] = {
    val ca: Codec[A] = SchemaVisitorCirceCodec.fromSchema(schema, cache)
    val encB: Encoder[B] = ca.contramap[B](bijection.from)
    val decB: Decoder[B] = ca.map(bijection.to)
    Codec.from(decB, encB)
  }

  override def refine[A, B](schema: Schema[A], refinement: Refinement[A, B]): Codec[B] = {
    val ca: Codec[A] = SchemaVisitorCirceCodec.fromSchema(schema, cache)
    val encB: Encoder[B] = ca.contramap(refinement.from)
    val decB: Decoder[B] = ca.emap(refinement.apply)
    Codec.from(decB, encB)
  }

  override def lazily[A](suspend: Lazy[Schema[A]]): Codec[A] = {
    lazy val compiled: Codec[A] = SchemaVisitorCirceCodec.fromSchema(suspend.value, cache)
    compiled
  }

  override def option[A](schema: Schema[A]): Codec[Option[A]] = {
    implicit val c: Codec[A] = SchemaVisitorCirceCodec.fromSchema(schema, cache)
    Codec.from(Decoder.decodeOption[A], Encoder.encodeOption[A])
  }

}

object DocumentFolder extends io.circe.Json.Folder[Option[Document]] {
  override def onNull: Option[Document] = DNull.some
  override def onBoolean(value: Boolean): Option[Document] = DBoolean(value).some
  override def onNumber(value: JsonNumber): Option[Document] = value.toBigDecimal.map(DNumber(_))
  override def onString(value: String): Option[Document] = DString(value).some
  override def onArray(value: Vector[Json]): Option[Document] = value.traverse(_.foldWith(this)).map(DArray(_))
  override def onObject(value: JsonObject): Option[Document] =
    value
      .toList
      .traverse { case (k, v) =>
        v.foldWith(this).map(k -> _)
      }
      .map(_.toMap)
      .map(DObject(_))
}
