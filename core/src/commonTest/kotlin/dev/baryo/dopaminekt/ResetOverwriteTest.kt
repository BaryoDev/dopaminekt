package dev.baryo.dopaminekt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResetOverwriteTest {
    private val log = mutableListOf<String>()
    private val events = mutableListOf<ProgressEvent>()
    private val storage = LoggingStorage(log)
    private val clock = TickClock()

    private fun build(): Progress {
        val progress = Progress(storage, ProgressConfig(clock = clock))
        progress.recordInto(log, events)
        return progress
    }

    private fun resets() = events.filterIsInstance<ProgressEvent.PlayerReset>()

    @Test
    fun overwriteFalseAfterFailedLoadKeepsStoredDocument() = runTest {
        val stored = """{"version":99}"""
        storage.values["dopamine_player"] = stored
        val progress = build()
        progress.init()
        assertTrue(progress.loadFailed)
        val snapshot = progress.reset(overwrite = false)
        assertEquals(stored, storage.values["dopamine_player"])
        assertTrue(progress.loadFailed)
        assertEquals(0.0, snapshot.num("xp"))
        assertEquals(1, resets().size)
    }

    @Test
    fun overwriteFalseAfterGoodLoadSaves() = runTest {
        val first = build()
        first.init()
        first.addXp(50.0)
        val progress = build()
        progress.init()
        assertEquals(50.0, progress.snapshot().num("xp"))
        events.clear()
        progress.reset(overwrite = false)
        assertFalse(progress.loadFailed)
        assertEquals(1, resets().size)
        val stored = Json.parseToJsonElement(storage.values.getValue("dopamine_player")).jsonObject
        assertTrue(stored.getValue("xpBy").jsonObject.isEmpty())
        assertEquals(0.0, build().also { it.init() }.snapshot().num("xp"))
    }

    @Test
    fun overwriteFalseAfterCorruptBackupSaves() = runTest {
        storage.values["dopamine_player"] = "{not json"
        val progress = build()
        progress.init()
        assertFalse(progress.loadFailed)
        assertEquals("\"{not json\"", storage.values["dopamine_player_corrupt"])
        log.clear()
        progress.reset(overwrite = false)
        assertTrue(storage.values.getValue("dopamine_player").startsWith("{\"v\":3"))
        assertEquals(1, storage.writes("player"))
    }

    @Test
    fun writesStayBlockedAfterOverwriteFalse() = runTest {
        val stored = """{"version":99}"""
        storage.values["dopamine_player"] = stored
        val progress = build()
        progress.init()
        progress.reset(overwrite = false)
        progress.addXp(10.0)
        assertEquals(stored, storage.values["dopamine_player"])
        assertTrue(progress.loadFailed)
    }
}
