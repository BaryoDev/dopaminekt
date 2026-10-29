package dev.baryo.dopaminekt

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
    fun emitRejectsACoreEventName() {
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
    fun pluginNamesMatchTheRule() {
        assertEquals(false, isValidPluginName(""))
        assertEquals(false, isValidPluginName("Abc"))
        assertEquals(false, isValidPluginName("a:b"))
        assertEquals(false, isValidPluginName("a b"))
        assertEquals(true, isValidPluginName("a-1"))
        assertEquals(true, isValidPluginName("spin"))
    }

    @Test
    fun partNamesRoundTripTheirWireNames() {
        for (part in PartName.entries) assertEquals(part, PartName.fromWireName(part.wireName))
        assertEquals(listOf("ui", "particles", "sound", "haptics"), PartName.entries.map { it.wireName })
        assertNull(PartName.fromWireName("video"))
        assertEquals(BurstKind.Coins, BurstKind.fromWireName("coins"))
        assertNull(BurstKind.fromWireName("rain"))
        assertEquals(PopupKind.Notice, PopupKind.fromWireName("notice"))
        assertEquals(PopupTier.Legendary, PopupTier.fromWireName("legendary"))
    }
}
