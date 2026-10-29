package dev.baryo.dopaminekt

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrictJsonTest {
    @Test
    fun textJsRejectsIsRefused() {
        val bad = listOf(
            "garbage", "tru", "NaN", "'x'", """{"a":abc}""", "Infinity", "-Infinity", "undefined",
            "01", "+1", ".5", "1.", "[tru]", """{"a":[1,NaN]}""", "0x10", "-", "{not json",
        )
        for (text in bad) {
            assertFailsWith<SerializationException>(text) { parseJsonStrict(text) }
        }
    }

    @Test
    fun validTextIsAccepted() {
        val good = listOf(
            "null", "true", "false", "0", "-0", "10", "1.5", "-2.25e+3", "1E5", "1e999", "\"text\"",
            "[]", "{}", """{"v":3,"xp":{"a":1e999,"b":[true,false,null,0.5]},"s":"NaN"}""",
        )
        for (text in good) parseJsonStrict(text)
        assertEquals("""{"a":"NaN"}""", parseJsonStrict("""{"a":"NaN"}""").toString())
    }
}
