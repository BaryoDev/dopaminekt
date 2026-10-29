package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventsTest {
    private val xp = ProgressEvent.XpGained(10.0, "Played tap", leveledUp = false, newLevel = 1, total = 10.0)

    @Test
    fun wireNamesMatchTheJsNames() {
        assertEquals("xp_gained", xp.wireName)
        assertEquals("level_up", ProgressEvent.LevelUp(1, 2, 100.0).wireName)
        assertEquals("achievement_unlocked", ProgressEvent.AchievementUnlocked("a", "n", "d", "i", 1.0).wireName)
        assertEquals("streak_updated", ProgressEvent.StreakUpdated(2, 2).wireName)
        assertEquals("personal_best", ProgressEvent.PersonalBest("tap", 5.0).wireName)
        assertEquals("player_reset", ProgressEvent.PlayerReset.wireName)
        assertEquals("save_failed", ProgressEvent.SaveFailed("player").wireName)
        assertEquals("load_failed", ProgressEvent.LoadFailed("player", "corrupt", backedUp = true).wireName)
    }

    @Test
    fun listenersOnlyHearTheirName() {
        val emitter = EventEmitter()
        val heard = mutableListOf<ProgressEvent>()
        emitter.on("xp_gained") { heard += it }
        emitter.emit(ProgressEvent.PlayerReset)
        emitter.emit(xp)
        assertEquals(listOf<ProgressEvent>(xp), heard)
    }

    @Test
    fun unsubscribeStopsDelivery() {
        val emitter = EventEmitter()
        var count = 0
        val off = emitter.on("xp_gained") { count++ }
        emitter.emit(xp)
        off()
        emitter.emit(xp)
        assertEquals(1, count)
    }

    @Test
    fun theSameListenerRegistersOnce() {
        val emitter = EventEmitter()
        var count = 0
        val listener: (ProgressEvent) -> Unit = { count++ }
        emitter.on("xp_gained", listener)
        emitter.on("xp_gained", listener)
        emitter.emit(xp)
        assertEquals(1, count)
    }

    @Test
    fun aThrowingListenerIsSwallowedAndTheRestRun() {
        val errors = mutableListOf<Throwable>()
        val emitter = EventEmitter { errors += it }
        var after = 0
        emitter.on("xp_gained") { throw IllegalStateException("boom") }
        emitter.on("xp_gained") { after++ }
        emitter.emit(xp)
        assertEquals(1, after)
        assertEquals(1, errors.size)
    }

    @Test
    fun cancellationFromAListenerIsRethrown() {
        val emitter = EventEmitter()
        emitter.on("xp_gained") { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { emitter.emit(xp) }
    }

    @Test
    fun aListenerAddedDuringAnEmitIsNotCalledForIt() {
        val emitter = EventEmitter()
        var late = 0
        emitter.on("xp_gained") { emitter.on("xp_gained") { late++ } }
        emitter.emit(xp)
        assertEquals(0, late)
        emitter.emit(xp)
        assertEquals(1, late)
    }

    @Test
    fun clearRemovesEveryListener() {
        val emitter = EventEmitter()
        var count = 0
        emitter.on("xp_gained") { count++ }
        emitter.on("level_up") { count++ }
        emitter.clear()
        emitter.emit(xp)
        emitter.emit(ProgressEvent.LevelUp(1, 2, 100.0))
        assertEquals(0, count)
    }

    @Test
    fun loadFailedCarriesTheErrorOnlyWhenThereIsOne() {
        val error = DopamineError("invalid_document")
        val event = ProgressEvent.LoadFailed("player", "invalid_document", error = error)
        assertTrue(event.error === error)
        assertEquals(null, event.backedUp)
    }
}
