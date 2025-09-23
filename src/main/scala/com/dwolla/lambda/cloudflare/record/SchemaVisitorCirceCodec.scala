package com.dwolla.lambda.cloudflare.record

import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import smithy.api.TimestampFormat
import smithy4s.*
import smithy4s.Document.{DArray, DBoolean, DNull, DNumber, DObject, DString}
import smithy4s.schema.{Alt, CachedSchemaCompiler, CollectionTag, CompilationCache, EnumTag, EnumValue, Field, Primitive, Schema, SchemaVisitor}
import smithy4s.json.Json

import java.util.Base64
import scala.util.Try

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
    case DArray(value) => io.circe.Json.fromValues(value.map(_.asJson(documentEncoder)))
    case DObject(value) => io.circe.Json.fromFields(value.map { case (k, v) => k -> v.asJson(documentEncoder) })
  }
  private implicit def documentDecoder: Decoder[Document] = Decoder.instance { c =>
    c.value.foldWith(DocumentFolder).toRight(DecodingFailure("Could not decode document", c.history))
  }

  private implicit val timestampEncoder: Encoder[Timestamp] = Encoder[String].contramap(_.format(TimestampFormat.DATE_TIME))
  private implicit val timestampDecoder: Decoder[Timestamp] = Decoder[String].emap(Timestamp.parse(_, TimestampFormat.DATE_TIME).toRight(s"Could not parse timestamp; expected ${Timestamp.showFormat(TimestampFormat.DATE_TIME)}"))

  override def primitive[P](shapeId: ShapeId,
                            hints: Hints, 
                            tag: Primitive[P]): Codec[P] = {
    implicit def implied[A: Encoder : Decoder]: Codec[A] = Codec.implied
    Primitive.deriving[Codec].apply(tag)
  }

  override def collection[C[_], A](shapeId: ShapeId, hints: Hints, tag: CollectionTag[C], member: Schema[A]): Codec[C[A]] = ???

  override def map[K, V](shapeId: ShapeId, hints: Hints, key: Schema[K], value: Schema[V]): Codec[Map[K, V]] = {


    io.circe.Json.obj()
  }

  override def enumeration[E](shapeId: ShapeId, hints: Hints, tag: EnumTag[E], values: List[EnumValue[E]], total: E => EnumValue[E]): Codec[E] = ???

  override def struct[S](shapeId: ShapeId, hints: Hints, fields: Vector[Field[S, _]], make: IndexedSeq[Any] => S): Codec[S] = ???

  override def union[U](shapeId: ShapeId, hints: Hints, alternatives: Vector[Alt[U, _]], dispatch: Alt.Dispatcher[U]): Codec[U] = ???

  override def biject[A, B](schema: Schema[A], bijection: Bijection[A, B]): Codec[B] = ???

  override def refine[A, B](schema: Schema[A], refinement: Refinement[A, B]): Codec[B] = ???

  override def lazily[A](suspend: Lazy[Schema[A]]): Codec[A] = ???

  override def option[A](schema: Schema[A]): Codec[Option[A]] = ???

}
