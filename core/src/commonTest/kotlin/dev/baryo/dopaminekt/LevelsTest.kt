package dev.baryo.dopaminekt

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LevelsTest {
    @Test
    fun everyBoundaryLandsOnItsThreshold() {
        assertEquals(1L, levelFor(0.0))
        assertEquals(1L, levelFor(99.0))
        assertEquals(2L, levelFor(100.0))
        assertEquals(2L, levelFor(299.0))
        assertEquals(3L, levelFor(300.0))
        assertEquals(4L, levelFor(600.0))
        assertEquals(5L, levelFor(1000.0))
    }

    @Test
    fun negativeXpIsLevelOne() {
        assertEquals(1L, levelFor(-50.0))
    }

    @Test
    fun thresholdForIsTheCurve() {
        assertEquals(0.0, thresholdFor(1))
        assertEquals(100.0, thresholdFor(2))
        assertEquals(300.0, thresholdFor(3))
        assertEquals(4500.0, thresholdFor(10))
    }

    @Test
    fun levelForAndThresholdForAgree() {
        for (level in 1L..200L) {
            assertEquals(level, levelFor(thresholdFor(level)), "at the start of level $level")
            if (level > 1) assertEquals(level - 1, levelFor(thresholdFor(level) - 0.5), "just before level $level")
        }
    }

    @Test
    fun theShownLevelIsNeverLowerThanTheStoredOne() {
        val doc = validate(
            buildJsonObject {
                put("v", 3)
                put("level", 7)
                putJsonObject("xpBy") { put("a", 100) }
            },
        )
        val shown = view(parseDoc(doc, 0, null, null), "2026-08-07")
        assertEquals(7L, (shown["level"] as JsonPrimitive).content.toLong())
    }

    @Test
    fun validateRefusesWhatIsNotADocument() {
        assertEquals("invalid_document", assertFailsWith<DopamineError> { validate(JsonPrimitive(1)) }.code)
        assertEquals(
            "unsupported_version",
            assertFailsWith<DopamineError> { validate(buildJsonObject { put("v", 2) }) }.code,
        )
    }
}
