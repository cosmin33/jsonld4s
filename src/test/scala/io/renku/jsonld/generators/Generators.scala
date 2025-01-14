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

package io.renku.jsonld.generators

import cats.data.NonEmptyList
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.numeric.{NonNegative, Positive}
import eu.timepit.refined.string.Url
import io.circe.{Encoder, Json}
import org.scalacheck.Gen._
import org.scalacheck.{Arbitrary, Gen}

import java.time._
import java.time.temporal.ChronoUnit.{DAYS, MINUTES => MINS}
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

object Generators {

  type NonBlank = String Refined NonEmpty

  def nonEmptyStrings(maxLength: Int = 10, charsGenerator: Gen[Char] = alphaChar): Gen[String] = {
    require(maxLength > 0)
    nonBlankStrings(maxLength = maxLength, charsGenerator = charsGenerator) map (_.value)
  }

  def nonBlankStrings(minLength: Int = 1, maxLength: Int = 10, charsGenerator: Gen[Char] = alphaChar): Gen[NonBlank] = {
    require(minLength <= maxLength)

    val lengths =
      if (maxLength == 1) const(maxLength)
      else if (minLength == maxLength) const(maxLength)
      else frequency(1 -> choose(minLength, maxLength), 9 -> choose(minLength + 1, maxLength))

    for {
      length <- lengths
      chars  <- listOfN(length, charsGenerator)
    } yield Refined.unsafeApply(chars.mkString(""))
  }

  def stringsOfLength(length: Int = 10, charsGenerator: Gen[Char] = alphaChar): Gen[String] =
    listOfN(length, charsGenerator).map(_.mkString(""))

  val emails: Gen[String] = {
    val firstCharGen    = frequency(6 -> alphaChar, 2 -> numChar, 1 -> oneOf("!#$%&*+-/=?_~".toList))
    val nonFirstCharGen = frequency(6 -> alphaChar, 2 -> numChar, 1 -> oneOf("!#$%&*+-/=?_~.".toList))
    val beforeAts = for {
      firstChar  <- firstCharGen
      otherChars <- nonEmptyList(nonFirstCharGen, minElements = 5, maxElements = 10)
    } yield s"$firstChar${otherChars.toList.mkString("")}"

    for {
      beforeAt <- beforeAts
      afterAt  <- nonEmptyStrings()
    } yield s"$beforeAt@$afterAt"
  }

  def paragraphs(minElements: Int = 5, maxElements: Int = 10): Gen[NonBlank] =
    nonEmptyStringsList(minElements, maxElements) map (_.mkString(" ")) map Refined.unsafeApply

  def sentences(minWords: Int = 1, maxWords: Int = 10): Gen[NonBlank] =
    nonEmptyStringsList(minWords, maxWords) map (_.mkString(" ")) map Refined.unsafeApply

  def sentenceContaining(phrase: NonBlank): Gen[NonBlank] = for {
    prefix <- nonEmptyStrings()
    suffix <- nonEmptyStrings()
  } yield Refined.unsafeApply(s"$prefix $phrase $suffix")

  def blankStrings(maxLength: Int Refined NonNegative = 10): Gen[String] =
    for {
      length <- choose(0, maxLength.value)
      chars  <- listOfN(length, const(" "))
    } yield chars.mkString("")

  def nonEmptyStringsList(minElements: Int = 1, maxElements: Int = 5): Gen[List[String]] = for {
    size  <- choose(minElements, maxElements)
    lines <- Gen.listOfN(size, nonEmptyStrings())
  } yield lines

  def nonEmptyList[T](generator: Gen[T], minElements: Int = 1, maxElements: Int = 5): Gen[NonEmptyList[T]] =
    for {
      size <- choose(minElements, maxElements)
      list <- Gen.listOfN(size, generator)
    } yield NonEmptyList.fromListUnsafe(list)

  def nonEmptySet[T](generator: Gen[T], minElements: Int = 1, maxElements: Int = 5): Gen[Set[T]] = for {
    size <- choose(minElements, maxElements)
    set  <- Gen.containerOfN[Set, T](size, generator)
  } yield set

  def listOf[T](generator: Gen[T], maxElements: Int = 5): Gen[List[T]] = for {
    size <- choose(0, maxElements)
    list <- Gen.listOfN(size, generator)
  } yield list

  def setOf[T](generator: Gen[T], maxElements: Int = 5): Gen[Set[T]] = for {
    size <- choose(0, maxElements)
    set  <- Gen.containerOfN[Set, T](size, generator)
  } yield set

  def positiveInts(max: Int = 1000): Gen[Int Refined Positive] =
    choose(1, max) map Refined.unsafeApply

  def positiveLongs(max: Long = 1000): Gen[Long Refined Positive] =
    choose(1L, max) map Refined.unsafeApply

  def nonNegativeInts(max: Int = 1000): Gen[Int Refined NonNegative] = choose(0, max) map Refined.unsafeApply

  def negativeInts(min: Int = -1000): Gen[Int] = choose(min, 0)

  def durations(max: FiniteDuration = 5 seconds): Gen[FiniteDuration] =
    choose(1, max.toMillis)
      .map(FiniteDuration(_, MILLISECONDS))

  def relativePaths(minSegments: Int = 1, maxSegments: Int = 10): Gen[String] = {
    require(minSegments <= maxSegments,
            s"Generate relative paths with minSegments=$minSegments and maxSegments=$maxSegments makes no sense"
    )

    for {
      partsNumber <- Gen.choose(minSegments, maxSegments)
      partsGenerator = nonEmptyStrings(
                         charsGenerator = frequency(9 -> alphaChar, 1 -> oneOf('-', '_'))
                       )
      parts <- Gen.listOfN(partsNumber, partsGenerator)
    } yield parts.mkString("/")
  }

  val httpPorts: Gen[Int Refined Positive] = choose(1000, 10000) map Refined.unsafeApply

  def httpUrls(pathGenerator: Gen[String] = relativePaths(minSegments = 0, maxSegments = 2)): Gen[String] =
    for {
      protocol <- Arbitrary.arbBool.arbitrary map {
                    case true  => "http"
                    case false => "https"
                  }
      port <- httpPorts
      host <- nonEmptyStrings()
      path <- pathGenerator
      pathValidated = if (path.isEmpty) "" else s"/$path"
    } yield s"$protocol://$host:$port$pathValidated"

  val localHttpUrls: Gen[String] = for {
    protocol <- Arbitrary.arbBool.arbitrary map {
                  case true  => "http"
                  case false => "https"
                }
    port <- httpPorts
  } yield s"$protocol://localhost:$port"

  val validatedUrls: Gen[String Refined Url] = httpUrls() map Refined.unsafeApply

  val shas: Gen[String] = for {
    length <- Gen.choose(40, 40)
    chars  <- Gen.listOfN(length, Gen.oneOf((0 to 9).map(_.toString) ++ ('a' to 'f').map(_.toString)))
  } yield chars.mkString("")

  implicit val timestamps: Gen[Instant] =
    Gen
      .choose(Instant.EPOCH.toEpochMilli, Instant.now().plus(2000, DAYS).toEpochMilli)
      .map(Instant.ofEpochMilli)

  val timestampsNotInTheFuture: Gen[Instant] =
    Gen
      .choose(Instant.EPOCH.toEpochMilli, Instant.now().toEpochMilli)
      .map(Instant.ofEpochMilli)

  val timestampsInTheFuture: Gen[Instant] =
    Gen
      .choose(Instant.now().plus(10, MINS).toEpochMilli, Instant.now().plus(2000, DAYS).toEpochMilli)
      .map(Instant.ofEpochMilli)

  implicit val zonedDateTimes: Gen[ZonedDateTime] =
    timestamps
      .map(ZonedDateTime.ofInstant(_, ZoneId.systemDefault))

  implicit val localDates: Gen[LocalDate] =
    timestamps
      .map(LocalDateTime.ofInstant(_, ZoneOffset.UTC))
      .map(_.toLocalDate)

  val localDatesNotInTheFuture: Gen[LocalDate] =
    timestampsNotInTheFuture
      .map(LocalDateTime.ofInstant(_, ZoneOffset.UTC))
      .map(_.toLocalDate)

  implicit val exceptions: Gen[Exception] = nonEmptyStrings(20).map(new Exception(_))
  implicit val nestedExceptions: Gen[Exception] = for {
    nestLevels <- positiveInts(5)
    rootCause  <- exceptions
  } yield {
    import Implicits._
    (1 to nestLevels).foldLeft(rootCause) { (nestedException, _) =>
      new Exception(nonEmptyStrings().generateOne, nestedException)
    }
  }

  implicit val jsons: Gen[Json] = {
    import io.circe.syntax._

    val tuples = for {
      key <- nonEmptyStrings(maxLength = 5)
      value <- oneOf(nonEmptyStrings(maxLength = 5),
                     Arbitrary.arbNumber.arbitrary,
                     Arbitrary.arbBool.arbitrary,
                     Gen.nonEmptyListOf(nonEmptyStrings())
               )
    } yield key -> value

    val objects = for {
      propertiesNumber <- positiveInts(5)
      tuples           <- Gen.listOfN(propertiesNumber, tuples)
    } yield Map(tuples: _*)

    implicit val mapEncoder: Encoder[Map[String, Any]] = Encoder.instance[Map[String, Any]] { map =>
      Json.obj(
        map.map {
          case (key, value: String)  => key -> Json.fromString(value)
          case (key, value: Number)  => key -> Json.fromBigDecimal(value.doubleValue())
          case (key, value: Boolean) => key -> Json.fromBoolean(value)
          case (key, value: List[_]) => key -> Json.arr(value.map(_.toString).map(Json.fromString): _*)
          case (_, value) =>
            throw new NotImplementedError(
              s"Add support for values of type '${value.getClass}' in the jsons generator"
            )
        }.toSeq: _*
      )
    }

    objects.map(_.asJson)
  }

  object Implicits {

    implicit class GenOps[T](generator: Gen[T]) {

      def generateOne: T = generator.sample getOrElse generateOne

      def generateNonEmptyList(minElements: Int = 1, maxElements: Int = 5): NonEmptyList[T] =
        nonEmptyList(generator, minElements, maxElements).sample
          .getOrElse(generateNonEmptyList(minElements, maxElements))

      def generateOption: Option[T] = Gen.option(generator).sample getOrElse generateOption

      def generateDifferentThan(value: T): T = {
        val generated = generator.sample.getOrElse(generateDifferentThan(value))
        if (generated == value) generateDifferentThan(value)
        else generated
      }

      def toGeneratorOfNonEmptyList(minElements: Int = 1, maxElements: Int = 5): Gen[NonEmptyList[T]] =
        nonEmptyList(generator, minElements, maxElements)
    }

    implicit class GenTuple2Ops[T, U](generator: Gen[(T, U)]) {
      def generateNonEmptyMap(minElements: Int = 1, maxElements: Int = 5): Map[T, U] = {

        val numberOfElementsInMap = Gen.choose(minElements, maxElements).generateOne

        @tailrec
        def addNextEntry(acc: Map[T, U]): Map[T, U] =
          if (acc.size == numberOfElementsInMap)
            acc
          else
            addNextEntry(acc + generator.generateOne)

        addNextEntry(Map.empty[T, U])
      }
    }

    implicit def asArbitrary[T](implicit generator: Gen[T]): Arbitrary[T] = Arbitrary(generator)

    implicit def asGen[T](arbitrary: Arbitrary[T]): Gen[T] = arbitrary.arbitrary
  }
}
