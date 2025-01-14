/*
 * Copyright 2023 Swiss Data Science Center (SDSC)
 * A partnership between École Polytechnique Fédérale de Lausanne (EPFL) and
 * Eidgenössische Technische Hochschule Zürich (ETHZ).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.renku.jsonld

import cats.Contravariant
import cats.data.NonEmptyList
import cats.syntax.contravariant._

import java.time.{Instant, LocalDate}

/** A type class that provides a conversion from a value of type `A` to a [[JsonLD]] value.
  */
trait JsonLDEncoder[A] extends Serializable {
  def apply(a: A): JsonLD
}

object JsonLDEncoder {

  implicit val contravariantForJsonLDEncoder: Contravariant[JsonLDEncoder] = new Contravariant[JsonLDEncoder] {
    def contramap[A, B](fa: JsonLDEncoder[A])(f: B => A): JsonLDEncoder[B] =
      JsonLDEncoder[B](b => fa(f(b)))
  }

  def apply[A](implicit instance: JsonLDEncoder[A]): JsonLDEncoder[A] = instance

  final def instance[A](f: A => JsonLD): JsonLDEncoder[A] = (a: A) => f(a)

  final def entityId[A](f: A => EntityId): JsonLDEncoder[A] = (a: A) => JsonLD.fromEntityId(f(a))

  final implicit def encodeOption[A](implicit valueEncoder: JsonLDEncoder[A]): JsonLDEncoder[Option[A]] =
    (a: Option[A]) => JsonLD.fromOption(a)

  final implicit def encodeSeq[A](implicit itemEncoder: JsonLDEncoder[A]): JsonLDEncoder[Seq[A]] =
    (seq: Seq[A]) => JsonLD.arr(seq map (itemEncoder(_)): _*)

  final implicit def encodeList[A](implicit itemEncoder: JsonLDEncoder[A]): JsonLDEncoder[List[A]] =
    (seq: List[A]) => JsonLD.arr(seq map (itemEncoder(_)): _*)

  final implicit def encodeSet[A](implicit
      itemEncoder: JsonLDEncoder[A],
      ordering:    Ordering[A]
  ): JsonLDEncoder[Set[A]] =
    (seq: Set[A]) => JsonLD.arr(seq.toList.sorted map (itemEncoder(_)): _*)

  final implicit def encodeNonEmptyList[A](implicit itemEncoder: JsonLDEncoder[A]): JsonLDEncoder[NonEmptyList[A]] =
    encodeList[A].contramap(_.toList)

  final implicit val encodeString:    JsonLDEncoder[String]    = (a: String) => JsonLD.fromString(a)
  final implicit val encodeInt:       JsonLDEncoder[Int]       = (a: Int) => JsonLD.fromInt(a)
  final implicit val encodeLong:      JsonLDEncoder[Long]      = (a: Long) => JsonLD.fromLong(a)
  final implicit val encodeInstant:   JsonLDEncoder[Instant]   = (a: Instant) => JsonLD.fromInstant(a)
  final implicit val encodeLocalDate: JsonLDEncoder[LocalDate] = (a: LocalDate) => JsonLD.fromLocalDate(a)
  final implicit val encodeSchema:    JsonLDEncoder[Schema]    = (a: Schema) => JsonLD.fromEntityId(a.toString)
  final implicit val encodeEntityId:  JsonLDEncoder[EntityId]  = JsonLD.fromEntityId
  final implicit val encodeJsonLD:    JsonLDEncoder[JsonLD]    = identity
  final implicit val encodeBoolean:   JsonLDEncoder[Boolean]   = (a: Boolean) => JsonLD.fromBoolean(a)

  // The circe JsonNumber only allows to be created out of strings. The Json.fromBigDecimal
  // returns a wrapper value, but in this case `asNumber` is always set.
  final implicit val encodeBigDecimal: JsonLDEncoder[BigDecimal] = (n: BigDecimal) =>
    JsonLD.fromNumber(
      io.circe.Json.fromBigDecimal(n).asNumber.getOrElse(sys.error("BigDecimal is not a number"))
    )
}
