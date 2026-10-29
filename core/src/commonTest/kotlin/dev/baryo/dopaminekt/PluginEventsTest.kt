package dev.baryo.dopaminekt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class PluginEventsTest {
    private val errors = mutableListOf<Throwable>()
    private val progress = Progress(MemoryStorage(), ProgressConfig(onError = { errors += it }))

    @Test
    fun customEventsReachListenersInOrder() {
        val heard = mutableListOf<String>()
        progress.on("spin:done") { heard += "first" }
        progress.on("spin:done") { heard += "second" }
        progress.on("spin:other") { heard += "wrong" }
        progress.emit(ProgressEvent.Custom("spin:done", JsonPrimitive(1)))
        heard += "after"
        assertEquals(listOf("first", "second", "after"), heard)
    }

    @Test
    fun listenersGetTheDataAndTheName() {
        val seen = mutableListOf<ProgressEvent.Custom>()
        progress.on("spin:done") { seen += it as ProgressEvent.Custom }
        val data = JsonPrimitive("x")
        progress.emit(ProgressEvent.Custom("spin:done", data))
        progress.emit(ProgressEvent.Custom("spin:done"))
        assertEquals("spin:done", seen[0].wireName)
        assertSame(data, seen[0].data)
        assertNull(seen[1].data)
    }

    @Test
    fun payloadIsTheDataUnchanged() {
        assertEquals(JsonPrimitive(2), ProgressEvent.Custom("a:b", JsonPrimitive(2)).payload())
        assertNull(ProgressEvent.Custom("a:b").payload())
        assertSame(JsonNull, ProgressEvent.Custom("a:b", JsonNull).payload())
    }

    @Test
    fun emitRejectsACoreEventNameForLackingAColon() {
        val heard = mutableListOf<ProgressEvent>()
        progress.on("xp_gained") { heard += it }
        val error = assertFailsWith<DopamineError> { progress.emit(ProgressEvent.Custom("xp_gained")) }
        assertEquals("plugin_event", error.code)
        assertEquals(0, heard.size)
    }

    @Test
    fun emitRejectsALeadingColonAndAcceptsAnEmptyEventPart() {
        val error = assertFailsWith<DopamineError> { progress.emit(ProgressEvent.Custom(":x")) }
        assertEquals("plugin_event", error.code)
        var count = 0
        progress.on("a:") { count++ }
        progress.emit(ProgressEvent.Custom("a:"))
        assertEquals(1, count)
    }

    @Test
    fun aThrowingListenerIsReportedAndTheRestStillRun() {
        val boom = IllegalStateException("boom")
        var later = 0
        progress.on("spin:done") { throw boom }
        progress.on("spin:done") { later++ }
        progress.emit(ProgressEvent.Custom("spin:done"))
        assertEquals(listOf<Throwable>(boom), errors)
        assertEquals(1, later)
    }

    @Test
    fun emitNamesFollowTheColonRule() {
        for (name in listOf("", ":", "::", "spin", ":x")) {
            val error = assertFailsWith<DopamineError>(name) { progress.emit(ProgressEvent.Custom(name)) }
            assertEquals("plugin_event", error.code, name)
        }
        for (name in listOf("a:", "a:b:c", "xp_gained:x")) {
            var count = 0
            progress.on(name) { count++ }
            progress.emit(ProgressEvent.Custom(name))
            assertEquals(1, count, name)
        }
    }

    @Test
    fun pluginNamesMatchTheRule() {
        val valid = listOf("a-1", "spin", "-", "123")
        val invalid = listOf("", "Abc", "a:b", "a b", "a_b", "\u00e9", "a\n")
        for (name in valid) assertEquals(true, isValidPluginName(name), name)
        for (name in invalid) assertEquals(false, isValidPluginName(name), name)
    }

    @Test
    fun wireNamesAreTheJsStringsAndRoundTrip() {
        assertEquals(listOf("confetti", "sparkle", "fire", "stars", "coins"), BurstKind.entries.map { it.wireName })
        assertEquals(listOf("achievement", "level", "notice", "custom"), PopupKind.entries.map { it.wireName })
        assertEquals(listOf("common", "rare", "legendary"), PopupTier.entries.map { it.wireName })
        assertEquals(listOf("ui", "particles", "sound", "haptics"), PartName.entries.map { it.wireName })
        for (v in BurstKind.entries) assertEquals(v, BurstKind.fromWireName(v.wireName))
        for (v in PopupKind.entries) assertEquals(v, PopupKind.fromWireName(v.wireName))
        for (v in PopupTier.entries) assertEquals(v, PopupTier.fromWireName(v.wireName))
        for (v in PartName.entries) assertEquals(v, PartName.fromWireName(v.wireName))
        for (bad in listOf("", "UI", " ui", "Confetti")) {
            assertNull(BurstKind.fromWireName(bad), bad)
            assertNull(PopupKind.fromWireName(bad), bad)
            assertNull(PopupTier.fromWireName(bad), bad)
            assertNull(PartName.fromWireName(bad), bad)
        }
    }

    @Test
    fun pluginStorageUsesTheExactPrefixAndKeepsPluginsApart() = runTest {
        val base = MemoryStorage()
        val a = pluginStorage(base, "spin")
        val b = pluginStorage(base, "other")
        a.set("k", "1")
        assertEquals("1", base.get("dopamine_plugin_spin:k"))
        assertEquals("1", a.get("k"))
        assertNull(b.get("k"))
        b.set("k", "2")
        assertEquals("2", base.get("dopamine_plugin_other:k"))
        a.remove("k")
        assertNull(base.get("dopamine_plugin_spin:k"))
        assertEquals("2", b.get("k"))
        val error = assertFailsWith<DopamineError> { pluginStorage(base, "Bad") }
        assertEquals("plugin_invalid", error.code)
    }
}
