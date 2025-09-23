package io.circe

import com.github.plokhotnyuk.jsoniter_scala.core._

/**
 * Bridge utilities for using a Jsoniter JsonValueCodec[A] as a Circe Codec[A].
 *
 * This is a minimal adapter that:
 * - Encodes by serializing with Jsoniter to a compact JSON string and parsing it into io.circe.Json
 * - Decodes by printing a compact JSON string from io.circe.Json and reading it with Jsoniter
 *
 * It relies on the jsoniter-scala-core and circe-parser modules transitively (parser is part of circe-core in 0.14.x via jawn).
 */
object JsoniterScalaCodec {
  /** Build a Circe Codec[A] from a Jsoniter JsonValueCodec[A]. */
  def fromJsoniter[A](implicit jc: JsonValueCodec[A]): Codec[A] = {
    val enc: Encoder[A] = Encoder.instance { a =>
      // Use Jsoniter to serialize, then parse into Circe Json
      val s = writeToString(a)(jc)
      io.circe.parser.parse(s) match {
        case Right(json) => json
        case Left(err) =>
          // Encoder can't fail in its type, so throw if something goes very wrong
          throw err
      }
    }

    val dec: Decoder[A] = Decoder.instance { c =>
      // Render the incoming Circe Json to a compact string, then read with Jsoniter
      val jsonStr = Printer.noSpaces.print(c.value)
      try {
        Right(readFromString[A](jsonStr)(jc))
      } catch {
        case e: JsonReaderException => Left(DecodingFailure(e.getMessage, c.history))
        case e: Throwable => Left(DecodingFailure(e.getMessage, c.history))
      }
    }

    Codec.from(dec, enc)
  }

  /** Implicitly provide a Circe Codec[A] wherever a Jsoniter JsonValueCodec[A] is in scope. */
  implicit def circeCodecFromJsoniter[A](implicit jc: JsonValueCodec[A]): Codec[A] = fromJsoniter[A]
}
