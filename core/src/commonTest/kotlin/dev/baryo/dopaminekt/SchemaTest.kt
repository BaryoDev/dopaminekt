package dev.baryo.dopaminekt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks documents against spec/core/schema.json with a small checker for the
 * keywords the schema uses: type, const, required, properties,
 * additionalProperties, items, pattern, minimum and $ref.
 */
class SchemaTest {
    private val schema = Json.parseToJsonElement(FixtureData.schema) as JsonObject

    private fun numbersEqual(a: JsonElement?, b: JsonElement?): Boolean {
        val x = (a as? JsonPrimitive)?.takeIf { !it.isString }
        val y = (b as? JsonPrimitive)?.takeIf { !it.isString }
        val p = x.finiteNumber()
        val q = y.finiteNumber()
        return if (p != null && q != null) p == q else a == b
    }

    private fun check(node: JsonObject, value: JsonElement?, path: String = "$"): List<String> {
        (node["\$ref"] as? JsonPrimitive)?.let { ref ->
            var target: JsonElement = schema
            for (key in ref.content.removePrefix("#/").split("/")) target = (target as JsonObject).getValue(key)
            return check(target as JsonObject, value, path)
        }
        val errors = mutableListOf<String>()
        val type = (node["type"] as? JsonPrimitive)?.content
        node["const"]?.let { if (!numbersEqual(value, it)) errors += "$path: expected $it" }
        when (type) {
            "object" -> if (value !is JsonObject) return listOf("$path: expected object")
            "array" -> if (value !is JsonArray) return listOf("$path: expected array")
            "string" -> if (value !is JsonPrimitive || !value.isString) return listOf("$path: expected string")
            "boolean" -> if (value !is JsonPrimitive || value.isString || value.content !in setOf("true", "false")) {
                return listOf("$path: expected boolean")
            }
            "number" -> if (value.finiteNumber() == null) return listOf("$path: expected number")
            "integer" -> if (value.wholeNumber() == null) return listOf("$path: expected integer")
        }
        val number = value.finiteNumber()
        val minimum = node["minimum"].finiteNumber()
        if (number != null && minimum != null && number < minimum) errors += "$path: below $minimum"
        val pattern = (node["pattern"] as? JsonPrimitive)?.content
        if (value is JsonPrimitive && value.isString && pattern != null && !Regex(pattern).containsMatchIn(value.content)) {
            errors += "$path: does not match $pattern"
        }

        if (value is JsonObject && type == "object") {
            val properties = node["properties"] as? JsonObject
            for (key in (node["required"] as? JsonArray) ?: emptyList<JsonElement>()) {
                if ((key as JsonPrimitive).content !in value) errors += "$path: missing ${key.content}"
            }
            val extra = node["additionalProperties"]
            for ((key, child) in value) {
                val known = properties?.get(key)
                if (known != null) {
                    errors += check(known as JsonObject, child, "$path.$key")
                } else if (extra is JsonPrimitive && extra.content == "false") {
                    errors += "$path: unexpected $key"
                } else if (extra is JsonObject) {
                    errors += check(extra, child, "$path.$key")
                }
            }
        }
        if (value is JsonArray) {
            val items = node["items"] as? JsonObject
            if (items != null) value.forEachIndexed { i, item -> errors += check(items, item, "$path[$i]") }
        }
        return errors
    }

    private fun valid(doc: JsonElement): List<String> = check(schema, doc)

    private fun validated(doc: JsonElement, now: Long = 0): JsonObject? =
        try {
            validate(doc, now = now)
        } catch (error: DopamineError) {
            null
        }

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(FixtureData.files.getValue(name)) as JsonObject

    @Test
    fun schemaNamesContractThree() {
        assertEquals(3.0, (schema["properties"] as JsonObject).let { (it["v"] as JsonObject)["const"] }.finiteNumber())
    }

    @Test
    fun acceptsTheSmallestDocument() {
        assertEquals(emptyList(), valid(buildJsonObject { put("v", 3) }))
    }

    @Test
    fun refusesA2xDocumentUntilItIsMigrated() {
        assertTrue(valid(buildJsonObject { put("name", "Ana"); put("xp", 100); put("level", 2) }).isNotEmpty())
        assertTrue(valid(buildJsonObject { put("v", 2) }).isNotEmpty())
    }

    @Test
    fun refusesFieldsItDoesNotKnow() {
        assertEquals(listOf("$: unexpected extra"), valid(buildJsonObject { put("v", 3); put("extra", 1) }))
    }

    @Test
    fun refusesABadPlayDateAndANegativeAmount() {
        assertEquals(1, valid(buildJsonObject { put("v", 3); putJsonArray("playDates") { add(JsonPrimitive("2026-1-1")) } }).size)
        assertEquals(1, valid(buildJsonObject { put("v", 3); putJsonObject("xpBy") { put("a", -1) } }).size)
        assertEquals(1, valid(buildJsonObject { put("v", 3); putJsonObject("gamesBy") { put("a", 1.5) } }).size)
    }

    @Test
    fun holdsForEveryDocumentValidateReturnsFromTheMergeFixtures() {
        var checked = 0
        for (case in fixture("merge.json")["cases"] as JsonArray) {
            for (step in (case as JsonObject)["steps"] as JsonArray) {
                step as JsonObject
                if ((step["expect"] as JsonObject).containsKey("code")) continue
                for (key in listOf("a", "b", "doc")) {
                    val doc = step[key] as? JsonObject ?: continue
                    if (doc["v"].finiteNumber() != 3.0) continue
                    val out = validated(doc) ?: continue
                    checked++
                    assertEquals(emptyList(), valid(out), doc.toString())
                }
            }
        }
        assertTrue(checked > 5, "only $checked documents were checked")
    }

    @Test
    fun holdsForEverySavedPlayerTheMigrateFixturesLoad() {
        var checked = 0
        for (case in fixture("migrate.json")["cases"] as JsonArray) {
            val saved = (case as JsonObject)["savedJson"] as? JsonObject ?: continue
            val out = validated(saved, now = 1_786_093_200_000L) ?: continue
            checked++
            assertEquals(emptyList(), valid(out), case["name"].toString())
        }
        assertTrue(checked > 10, "only $checked saved players were checked")
    }
}
