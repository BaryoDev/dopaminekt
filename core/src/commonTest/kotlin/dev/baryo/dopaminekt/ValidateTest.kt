package dev.baryo.dopaminekt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidateTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun checked(text: String): JsonObject = validate(parse(text))

    @Test
    fun wholeNumbersPassAndFractionsDoNot() {
        assertEquals(2.0, JsonPrimitive(2.0).wholeNumber())
        assertEquals(1000.0, Json.parseToJsonElement("1e3").wholeNumber())
        assertEquals(null, JsonPrimitive(2.5).wholeNumber())
        assertEquals(null, JsonPrimitive("2").wholeNumber())
        assertEquals(null, Json.parseToJsonElement("1e999").wholeNumber())
        assertEquals(null, Json.parseToJsonElement("null").wholeNumber())
    }

    @Test
    fun countsAreCappedAtTheLargestSafeInteger() {
        val out = checked("""{"v":3,"gamesBy":{"a":1e300},"level":1e300}""")
        assertEquals("9007199254740991", ((out["gamesBy"] as JsonObject)["a"] as JsonPrimitive).content)
        assertEquals("9007199254740991", (out["level"] as JsonPrimitive).content)
    }

    @Test
    fun unsafeKeysAreNotKept() {
        val out = checked("""{"v":3,"xpBy":{"__proto__":5,"constructor":5,"prototype":5,"ok":5}}""")
        assertEquals(setOf("ok"), (out["xpBy"] as JsonObject).keys)
    }

    @Test
    fun badEntriesAreDroppedNotRejected() {
        val out = checked("""{"v":3,"xpBy":{"a":"5","b":-1,"c":1e999,"d":7},"gamesBy":{"a":1.5,"b":2},"playDates":["2026-02-30","2026-08-07",5]}""")
        assertEquals(setOf("d"), (out["xpBy"] as JsonObject).keys)
        assertEquals(setOf("b"), (out["gamesBy"] as JsonObject).keys)
        assertEquals("[\"2026-08-07\"]", out["playDates"].toString())
    }

    @Test
    fun wholeAmountsAreWrittenWithoutAFraction() {
        val out = checked("""{"v":3,"xpBy":{"a":100.0}}""")
        assertEquals("""{"a":100}""", out["xpBy"].toString())
    }

    @Test
    fun aVersionOtherThanThreeIsRefused() {
        for (v in listOf("2", "\"3\"", "null", "4")) {
            assertEquals("unsupported_version", assertFailsWith<DopamineError> { checked("""{"v":$v}""") }.code)
        }
        assertTrue(checked("""{"v":3.0}""").containsKey("v"))
    }

    @Test
    fun aTwoXSaveMovesUnderTheLegacyKey() {
        val out = validate(parse("""{"xp":500,"level":3,"totalGamesPlayed":7,"achievements":{"first_game":{"unlockedAt":1,"seen":true}}}"""))
        assertEquals("""{"legacy":450}""", out["xpBy"].toString())
        assertEquals("""{"legacy":7}""", out["gamesBy"].toString())
    }

    @Test
    fun mergeTakesTheLargerNumberPerDevice() {
        val out = merge(parse("""{"v":3,"xpBy":{"a":5,"b":1}}"""), parse("""{"v":3,"xpBy":{"a":3,"b":4}}"""))
        assertEquals("""{"a":5,"b":4}""", out["xpBy"].toString())
    }

    @Test
    fun moreThanTwentyDevicesIsRefused() {
        val keys = (1..21).joinToString(",") { "\"d$it\":1" }
        assertEquals("too_many_devices", assertFailsWith<DopamineError> { checked("""{"v":3,"xpBy":{$keys}}""") }.code)
        val twenty = (1..20).joinToString(",") { "\"d$it\":1" }
        assertFalse(checked("""{"v":3,"xpBy":{$twenty}}""").isEmpty())
    }

    @Test
    fun theSumOfASaveDoesNotDependOnKeyOrder() {
        val a = parseDoc(parse("""{"v":3,"xpBy":{"b":0.1,"a":0.2,"c":0.3}}"""), 0, null, null)
        val b = parseDoc(parse("""{"v":3,"xpBy":{"c":0.3,"a":0.2,"b":0.1}}"""), 0, null, null)
        assertEquals(xpOf(a), xpOf(b))
    }
}
