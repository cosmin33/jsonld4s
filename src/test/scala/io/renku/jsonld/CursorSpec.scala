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

import cats.syntax.all._
import eu.timepit.refined.auto._
import io.circe.DecodingFailure
import io.renku.jsonld.Cursor.FlattenedArrayCursor
import io.renku.jsonld.JsonLD.{JsonLDArray, JsonLDEntity}
import io.renku.jsonld.generators.Generators.Implicits._
import io.renku.jsonld.generators.Generators.nonEmptyStrings
import io.renku.jsonld.generators.JsonLDGenerators.{entityIds, entityTypes, entityTypesObject, jsonLDEdges, jsonLDEntities, jsonLDValues, properties, schemas, valuesProperties}
import io.renku.jsonld.syntax._
import org.scalacheck.{Arbitrary, Gen}
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CursorSpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers with MockFactory {

  "Empty" should {

    "show as 'Empty Cursor' if does not have a message" in new TestCase {
      Cursor.Empty().show shouldBe "Empty cursor"
    }

    "show as 'Empty Cursor' with the cause if it has a message" in new TestCase {
      val message = nonEmptyStrings().generateOne
      Cursor.Empty(message).show shouldBe s"Empty cursor cause by: $message"
    }
  }

  "as" should {

    "return success if cursor can be decoded to the requested type" in new TestCase {
      forAll(
        Gen.oneOf(jsonLDValues, jsonLDEntities, jsonLDEdges, entityIds.map(_.asJsonLD), jsonLDValues.map(JsonLD.arr(_)))
      )(json => json.cursor.as[JsonLD] shouldBe json.asRight[DecodingFailure])
    }
  }

  "getEntityTypes" should {

    "return entity's EntityTypes" in new TestCase {
      forAll { (id: EntityId, entityTypes: EntityTypes, property: (Property, JsonLD)) =>
        val cursor = JsonLD
          .entity(id, entityTypes, property)
          .cursor

        cursor.getEntityTypes shouldBe entityTypes.asRight[DecodingFailure]
      }
    }

    "return a failure for non-JsonLDEntity objects" in new TestCase {
      forAll(jsonLDValues) { value =>
        value.cursor.getEntityTypes shouldBe DecodingFailure("No EntityTypes found on non-JsonLDEntity object", Nil)
          .asLeft[EntityTypes]
      }
    }
  }

  "downEntityId" should {

    "return a Cursor pointing to entityId of the given entity" in new TestCase {
      forAll { (id: EntityId, entityType: EntityType, property: (Property, JsonLD)) =>
        val cursor = JsonLD
          .entity(id, EntityTypes.of(entityType), property)
          .cursor

        cursor.downEntityId.jsonLD shouldBe id.asJsonLD
      }
    }

    "return a Cursor pointing to the entityId object of a property if it's encoded as single-item list of entityIds" in new TestCase {
      val property      = properties.generateOne
      val childEntityId = entityIds.generateOne
      val cursor = JsonLD
        .entity(entityIds.generateOne, entityTypesObject.generateOne, property -> JsonLD.arr(childEntityId.asJsonLD))
        .cursor

      cursor.downField(property).downEntityId.jsonLD shouldBe childEntityId.asJsonLD
    }

    "return the same Cursor if it's a cursor on JsonLDEntityId" in new TestCase {
      val cursor = JsonLD.JsonLDEntityId(entityIds.generateOne).cursor
      cursor.downEntityId shouldBe cursor
    }

    forAll(
      Table(
        "case"     -> "List of EntityId",
        "multiple" -> entityIds.generateNonEmptyList(minElements = 2).toList.map(_.asJsonLD),
        "no"       -> List.empty[JsonLD]
      )
    ) { (caseName, entityIdsList) =>
      s"return an empty Cursor when called on a property with $caseName entityIds in an array" in new TestCase {
        val property = properties.generateOne
        val cursor = JsonLD
          .entity(entityIds.generateOne, entityTypesObject.generateOne, property -> JsonLD.arr(entityIdsList: _*))
          .cursor

        cursor.downField(property).downEntityId shouldBe Cursor.Empty(
          s"Expected @id but got an array of size ${entityIdsList.size}"
        )
      }
    }
  }

  "return an empty Cursor if object is not a JsonLDEntity" in new TestCase {
    JsonLD.fromInt(Arbitrary.arbInt.arbitrary.generateOne).cursor.downEntityId shouldBe Cursor.Empty(
      "Expected @id but got a JsonLDValue"
    )
  }

  "downType" should {

    "return a Cursor pointing to object of the given type" in new TestCase {
      forAll { (id: EntityId, entityType: EntityType, schema: Schema, property: (Property, JsonLD)) =>
        val searchedType = (schema / "type").asEntityType
        val cursor = JsonLD
          .entity(id, EntityTypes.of(entityType, searchedType), property)
          .cursor

        cursor.downType(searchedType) shouldBe cursor
      }
    }

    "return an empty Cursor if there is no object with the searched type(s)" in new TestCase {
      forAll { (id: EntityId, entityTypes: EntityTypes, schema: Schema, property: (Property, JsonLD)) =>
        val searchedType = (schema / "type").asEntityType
        JsonLD
          .entity(id, entityTypes, property)
          .cursor
          .downType(searchedType) shouldBe Cursor.Empty(show"Cannot find entity of $searchedType type")
      }
    }
  }

  "downArray" should {

    "return itself if called on an array wrapped in the FlattenedArrayCursor" in new TestCase {
      val cursor =
        FlattenedArrayCursor(Cursor.Empty(), JsonLDArray(jsonLDValues.generateNonEmptyList().toList), Map.empty)
      cursor.downArray shouldBe cursor
    }

    "return itself if called on an array wrapped in the Cursor" in new TestCase {
      val array  = JsonLDArray(jsonLDValues.generateNonEmptyList().toList)
      val cursor = Cursor.TopCursor(array)
      cursor.downArray shouldBe Cursor.ArrayCursor(cursor, array)
    }

    "return an empty Cursor if called not on an array" in new TestCase {
      Cursor.TopCursor(jsonLDValues.generateOne).downArray shouldBe Cursor.Empty(
        "Expected JsonLD Array but got JsonLDValue"
      )
    }
  }

  "downField" should {

    "return a Cursor pointing to a searched property" in new TestCase {
      forAll { (id: EntityId, entityTypes: EntityTypes, property1: (Property, JsonLD), property2: (Property, JsonLD)) =>
        val (prop1Name, prop1Value) = property1
        val cursor = JsonLD
          .entity(id, entityTypes, property1, property2)
          .cursor
          .downField(prop1Name)

        cursor.as[JsonLD] shouldBe prop1Value.asRight[DecodingFailure]
      }
    }

    "return an empty Cursor if given property cannot be found" in new TestCase {
      val field = properties.generateOne
      jsonLDEntities.generateOne.cursor.downField(field) shouldBe Cursor.Empty(s"Cannot find $field property")
    }

    "return an empty Cursor if given property points to object that cannot be associated with a property" in new TestCase {
      val field = properties.generateOne
      jsonLDEntities.generateOne.copy(properties = Map(field -> JsonLD.Null)).cursor.downField(field) shouldBe
        Cursor.Empty(s"$field property points to JsonLDNull")
    }

    "return an empty Cursor if called on neither JsonLDEntity nor JsonLDArray" in new TestCase {
      val field = properties.generateOne
      JsonLD.Null.cursor.downField(field) shouldBe Cursor.Empty("Expected JsonLD entity or array but got JsonLDNull")
      jsonLDValues.generateOne.cursor.downField(field) shouldBe Cursor.Empty(
        "Expected JsonLD entity or array but got JsonLDValue"
      )
      entityIds.generateOne.asJsonLD.cursor.downField(field) shouldBe Cursor.Empty(
        "Expected JsonLD entity or array but got JsonLDEntityId"
      )
      jsonLDEdges.generateOne.cursor.downField(field) shouldBe Cursor.Empty(
        "Expected JsonLD entity or array but got JsonLDEdge"
      )
    }
  }

  "delete" should {

    "allow to remove a selected field" in new TestCase {
      forAll { (id: EntityId, entityTypes: EntityTypes, property1: (Property, JsonLD), property2: (Property, JsonLD)) =>
        JsonLD
          .entity(id, entityTypes, property1, property2)
          .cursor
          .downField(property1._1)
          .delete
          .top shouldBe Some(JsonLD.entity(id, entityTypes, property2))
      }
    }

    "allow to remove a selected entity" in new TestCase {
      forAll { (id: EntityId, entityTypes: EntityTypes, property: (Property, JsonLD)) =>
        JsonLD
          .entity(id, entityTypes, property)
          .cursor
          .downType(entityTypes.toList.head)
          .delete
          .top shouldBe None
      }
    }
  }

  "findInCache(JsonLDDecoder)" should {

    "reach the cache for JsonLDEntityDecoder" in new TestCase {
      val decoder =
        JsonLDDecoder.cacheableEntity[String](entityTypesObject.generateOne)(_ => nonEmptyStrings().generateOne.asRight)
      val entity = jsonLDEntities.generateOne

      val fromCache = nonEmptyStrings().generateOne.some
      (decodingCache
        .get[String](_: EntityId)(_: CacheableEntityDecoder))
        .expects(entity.id, decoder.cacheableDecoder)
        .returning(fromCache)

      Cursor.TopCursor(entity).findInCache(decoder) shouldBe fromCache
    }

    "do not reach the cache for a non-JsonLDEntityDecoder" in new TestCase {
      val decoder = JsonLDDecoder.instance[String](_ => nonEmptyStrings().generateOne.asRight)
      val entity  = jsonLDEntities.generateOne

      Cursor.TopCursor(entity).findInCache(decoder) shouldBe None
    }
  }

  "findInCache(EntityId, JsonLDDecoder)" should {

    "reach the cache for JsonLDEntityDecoder" in new TestCase {
      val decoder =
        JsonLDDecoder.cacheableEntity[String](entityTypesObject.generateOne)(_ => nonEmptyStrings().generateOne.asRight)
      val entity @ JsonLDEntity(id, _, _, _) = jsonLDEntities.generateOne

      val fromCache = nonEmptyStrings().generateOne.some
      (decodingCache
        .get[String](_: EntityId)(_: CacheableEntityDecoder))
        .expects(id, decoder.cacheableDecoder)
        .returning(fromCache)

      Cursor.TopCursor(entity).findInCache(id, decoder) shouldBe fromCache
    }

    "do not reach the cache for a non-JsonLDEntityDecoder" in new TestCase {
      val decoder = JsonLDDecoder.instance[String](_ => nonEmptyStrings().generateOne.asRight)
      val entity  = jsonLDEntities.generateOne

      Cursor.TopCursor(entity).findInCache[String](entity.id, decoder) shouldBe None
    }
  }

  "findInCache(CacheableEntityDecoder)" should {

    "reach the cache for CacheableEntityDecoder" in new TestCase {
      val decoder =
        JsonLDDecoder.cacheableEntity[String](entityTypesObject.generateOne)(_ => nonEmptyStrings().generateOne.asRight)
      val entity @ JsonLDEntity(id, _, _, _) = jsonLDEntities.generateOne

      val fromCache = nonEmptyStrings().generateOne.some
      (decodingCache
        .get[String](_: EntityId)(_: CacheableEntityDecoder))
        .expects(id, decoder.cacheableDecoder)
        .returning(fromCache)

      Cursor.TopCursor(entity).findInCache(decoder.cacheableDecoder) shouldBe fromCache
    }
  }

  "cache(EntityId, A, JsonLDDecoder[A])" should {

    "put object A to the cache in case of JsonLDEntityDecoder" in new TestCase {
      val decoder =
        JsonLDDecoder.cacheableEntity[String](entityTypesObject.generateOne)(_ => nonEmptyStrings().generateOne.asRight)

      val entityId = entityIds.generateOne
      val obj      = nonEmptyStrings().generateOne

      (decodingCache
        .put[String](_: EntityId, _: String)(_: CacheableEntityDecoder))
        .expects(entityId, obj, decoder.cacheableDecoder)
        .returning(obj)

      Cursor.Empty().cache[String](entityId, obj, decoder) shouldBe obj
    }

    "do not put object A to the cache in case of a non-JsonLDEntityDecoder" in new TestCase {
      val decoder = JsonLDDecoder.instance[String](_ => nonEmptyStrings().generateOne.asRight)

      val entityId = entityIds.generateOne
      val obj      = nonEmptyStrings().generateOne

      Cursor.Empty().cache[String](entityId, obj, decoder) shouldBe obj
    }
  }

  "cache(EntityId, A, CacheableEntityDecoder[A])" should {

    "put object A to the cache in case of CacheableEntityDecoder" in new TestCase {
      val decoder =
        JsonLDDecoder.cacheableEntity[String](entityTypesObject.generateOne)(_ => nonEmptyStrings().generateOne.asRight)

      val entityId = entityIds.generateOne
      val obj      = nonEmptyStrings().generateOne

      (decodingCache
        .put[String](_: EntityId, _: String)(_: CacheableEntityDecoder))
        .expects(entityId, obj, decoder.cacheableDecoder)
        .returning(obj)

      Cursor.Empty().cache[String](entityId, obj)(decoder.cacheableDecoder) shouldBe obj
    }
  }

  "cache(JsonLD, A, JsonLDDecoder[A])" should {

    "put object A to the cache in case of JsonLDEntityDecoder and JsonLDEntity" in new TestCase {
      val decoder =
        JsonLDDecoder.cacheableEntity[String](entityTypesObject.generateOne)(_ => nonEmptyStrings().generateOne.asRight)

      val entity @ JsonLDEntity(id, _, _, _) = jsonLDEntities.generateOne
      val obj                                = nonEmptyStrings().generateOne

      (decodingCache
        .put[String](_: EntityId, _: String)(_: CacheableEntityDecoder))
        .expects(id, obj, decoder.cacheableDecoder)
        .returning(obj)

      Cursor.Empty().cache[String](entity.asInstanceOf[JsonLD], obj, decoder) shouldBe obj
    }

    "do not put object A to the cache in case of a non-JsonLDEntityDecoder" in new TestCase {
      val decoder = JsonLDDecoder.instance[String](_ => nonEmptyStrings().generateOne.asRight)

      val entity = jsonLDEntities.generateOne
      val obj    = nonEmptyStrings().generateOne

      Cursor.Empty().cache[String](entity.asInstanceOf[JsonLD], obj, decoder) shouldBe obj
    }

    "do not put object A to the cache in case of a non-JsonLDEntity json" in new TestCase {
      val decoder =
        JsonLDDecoder.cacheableEntity[String](entityTypesObject.generateOne)(_ => nonEmptyStrings().generateOne.asRight)

      val json = jsonLDValues.generateOne
      val obj  = nonEmptyStrings().generateOne

      Cursor.Empty().cache[String](json, obj, decoder) shouldBe obj
    }
  }

  "cache(JsonLDEntity, A, JsonLDDecoder[A])" should {

    "put object A to the cache in case of JsonLDEntityDecoder" in new TestCase {
      val decoder =
        JsonLDDecoder.cacheableEntity[String](entityTypesObject.generateOne)(_ => nonEmptyStrings().generateOne.asRight)

      val entity @ JsonLDEntity(id, _, _, _) = jsonLDEntities.generateOne
      val obj                                = nonEmptyStrings().generateOne

      (decodingCache
        .put[String](_: EntityId, _: String)(_: CacheableEntityDecoder))
        .expects(id, obj, decoder.cacheableDecoder)
        .returning(obj)

      Cursor.Empty().cache[String](entity, obj, decoder) shouldBe obj
    }

    "do not put object A to the cache in case of a non-JsonLDEntityDecoder" in new TestCase {
      val decoder = JsonLDDecoder.instance[String](_ => nonEmptyStrings().generateOne.asRight)

      val entity = jsonLDEntities.generateOne
      val obj    = nonEmptyStrings().generateOne

      Cursor.Empty().cache[String](entity, obj, decoder) shouldBe obj
    }
  }

  private trait TestCase {
    implicit val decodingCache: DecodingCache = mock[DecodingCache]
  }
}
