package dev.baryo.dopaminekt

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProgressLoadTest {
    private val log = mutableListOf<String>()
    private val events = mutableListOf<ProgressEvent>()
    private val storage = LoggingStorage(log)
    private val clock = TickClock()
    private val errors = mutableListOf<Throwable>()

    private fun build(
        store: LoggingStorage = storage,
        config: (ProgressConfig) -> ProgressConfig = { it },
    ): Progress {
        val progress = Progress(store, config(ProgressConfig(clock = clock, onError = { errors += it })))
        progress.recordInto(log, events)
        return progress
    }

    private fun loadFailures() = events.filterIsInstance<ProgressEvent.LoadFailed>()

    // Load safety

    @Test
    fun aCorruptSaveIsBackedUpAndSavingResumes() = runTest {
        storage.values["dopamine_player"] = "{not json"
        val progress = build()
        progress.init()
        assertFalse(progress.loadFailed)
        assertEquals("\"{not json\"", storage.values["dopamine_player_corrupt"])
        assertEquals(listOf(ProgressEvent.LoadFailed("player", "corrupt", backedUp = true)), loadFailures())
        progress.addXp(10.0)
        assertEquals(10.0, build().also { it.init() }.snapshot().num("xp"))
    }

    @Test
    fun aCorruptSaveThatCannotBeBackedUpBlocksSaving() = runTest {
        storage.values["dopamine_player"] = "{not json"
        storage.failSet = { it == "dopamine_player_corrupt" }
        val progress = build()
        progress.init()
        assertTrue(progress.loadFailed)
        assertEquals(false, loadFailures().single().backedUp)
        storage.log.clear()
        progress.addXp(10.0)
        assertEquals(0, storage.writes("player"))
        assertEquals("{not json", storage.values["dopamine_player"])
    }

    @Test
    fun aReadFailureBlocksSavingUntilReset() = runTest {
        storage.values["dopamine_player"] = """{"v":3,"name":"Old"}"""
        storage.failGet = { it == "dopamine_player" }
        val progress = build()
        progress.init()
        assertTrue(progress.loadFailed)
        val failure = loadFailures().single()
        assertEquals("read", failure.reason)
        assertNotNull(failure.error)
        progress.addXp(10.0)
        assertEquals(0, storage.writes("player"))

        storage.failGet = { false }
        progress.reset()
        assertFalse(progress.loadFailed)
        assertTrue(storage.values.getValue("dopamine_player").contains("\"name\":\"Player\""))
    }

    @Test
    fun aFailedRetryKeepsTheProgressMadeSinceAndALaterOneLoads() = runTest {
        storage.values["dopamine_player"] = """{"v":3,"name":"Old","xpBy":{"d1":40}}"""
        storage.failGet = { it == "dopamine_player" }
        val progress = build()
        progress.init()
        progress.addXp(10.0)
        progress.init()
        assertTrue(progress.loadFailed)
        assertEquals(10.0, progress.snapshot().num("xp"))

        storage.failGet = { false }
        progress.init()
        assertFalse(progress.loadFailed)
        assertEquals(40.0, progress.snapshot().num("xp"))
    }

    @Test
    fun aNewerVersionIsLeftAloneWithTheDopamineErrorCode() = runTest {
        storage.values["dopamine_player"] = """{"v":999}"""
        val progress = build()
        progress.init()
        assertTrue(progress.loadFailed)
        val failure = loadFailures().single()
        assertEquals("unsupported_version", failure.reason)
        assertTrue(failure.error is DopamineError)
        progress.addXp(10.0)
        assertEquals("""{"v":999}""", storage.values["dopamine_player"])
        assertNull(storage.values["dopamine_player_corrupt"])

        progress.reset()
        assertFalse(progress.loadFailed)
        assertTrue(storage.values.getValue("dopamine_player").startsWith("{\"v\":3"))
        assertNull(storage.values["dopamine_player_corrupt"])
    }

    @Test
    fun valuesThatAreNotRecordsStartEmptySilently() = runTest {
        for (text in listOf("null", "5", "[1,2]", "\"text\"", "")) {
            events.clear()
            val store = LoggingStorage()
            store.values["dopamine_player"] = text
            val progress = build(store)
            progress.init()
            assertFalse(progress.loadFailed, text)
            assertEquals(emptyList(), loadFailures(), text)
            assertNull(store.values["dopamine_player_corrupt"], text)
            assertEquals(0.0, progress.snapshot().num("xp"))
        }
    }

    @Test
    fun aSaveWithTooManyDevicesIsRefusedOnLoad() = runTest {
        val xpBy = (1..21).joinToString(",") { "\"d$it\":1" }
        storage.values["dopamine_player"] = """{"v":3,"xpBy":{$xpBy}}"""
        val progress = build()
        progress.init()
        assertTrue(progress.loadFailed)
        assertEquals("too_many_devices", loadFailures().single().reason)
    }

    @Test
    fun aWriteThatAddsADeviceOverTheCapIsRefused() = runTest {
        val xpBy = (1..20).joinToString(",") { "\"d$it\":1" }
        storage.values["dopamine_player"] = """{"v":3,"xpBy":{$xpBy}}"""
        val progress = build()
        progress.init()
        assertFalse(progress.loadFailed)
        events.clear()
        val result = progress.addXp(10.0)
        assertEquals(0.0, result.xpGained)
        assertEquals("too_many_devices", loadFailures().single().reason)
        assertFalse(progress.record("tap"))
    }

    // Device id

    @Test
    fun theDeviceIdIsSavedOnceAndKept() = runTest {
        val progress = build()
        progress.init()
        val saved = storage.values.getValue("dopamine_device")
        assertTrue(Regex("\"[0-9a-f]{16}\"").matches(saved), saved)
        progress.reset()
        assertEquals(saved, storage.values["dopamine_device"])
        progress.addXp(7.0)
        assertEquals(listOf(saved.trim('"')), progress.export().getValue("xpBy").jsonObject.keys.toList())
        assertEquals(1, log.count { it == "set:device" })
    }

    @Test
    fun theDeviceIdWriteIsRetriedAndNeverAnnounced() = runTest {
        var failures = 2
        storage.failSet = { key -> key == "dopamine_device" && failures-- > 0 }
        val progress = build()
        progress.init()
        assertNull(storage.values["dopamine_device"])
        progress.addXp(1.0)
        assertNull(storage.values["dopamine_device"])
        progress.addXp(1.0)
        assertNotNull(storage.values["dopamine_device"])
        assertFalse(events.any { it is ProgressEvent.SaveFailed })
        assertEquals(2, errors.size)
    }

    @Test
    fun aStoredLegacyOrUnsafeIdIsReplaced() = runTest {
        storage.values["dopamine_device"] = "\"legacy\""
        val progress = build()
        progress.init()
        assertTrue(Regex("\"[0-9a-f]{16}\"").matches(storage.values.getValue("dopamine_device")))
    }

    @Test
    fun aKeptIdCountsAsSavedAfterAReload() = runTest {
        build().init()
        val id = storage.values.getValue("dopamine_device")
        val second = build()
        second.init()
        assertEquals(id, storage.values["dopamine_device"])
    }

    // Reset (ported from RewardSystemReset)

    @Test
    fun resetReplacesThePlayerWithAnEmptyOne() = runTest {
        val progress = build()
        progress.init()
        progress.record("pickle", mapOf("score" to 40.0))
        progress.addXp(500.0, "bonus")
        val after = progress.reset()
        assertEquals(0.0, after.num("xp"))
        assertEquals(1.0, after.num("level"))
        assertEquals(JsonObject(emptyMap()), after.getValue("achievements"))
        assertEquals(JsonObject(emptyMap()), after.getValue("stats"))
        assertEquals(0.0, after.getValue("streak").jsonObject.num("current"))
        assertEquals(0.0, after.num("totalGamesPlayed"))
        assertEquals(emptyList(), progress.getUnseenAchievements())
        val doc = progress.export()
        assertEquals(JsonArray(emptyList()), doc.getValue("playDates"))
        assertEquals(JsonObject(emptyMap()), doc.getValue("best"))
    }

    @Test
    fun resetWritesOnce() = runTest {
        val progress = build()
        progress.init()
        progress.addXp(10.0)
        storage.log.clear()
        progress.reset()
        assertEquals(1, storage.writes("player"))
        assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(storage.values.getValue("dopamine_player")).jsonObject.getValue("xpBy"))
    }

    @Test
    fun laterActivityCountsUnderTheSameDevice() = runTest {
        val progress = build()
        progress.init()
        progress.addXp(10.0)
        val device = storage.values.getValue("dopamine_device").trim('"')
        progress.reset()
        progress.record("pickle", mapOf("score" to 5.0))
        val doc = progress.export()
        assertEquals(listOf(device), doc.getValue("gamesBy").jsonObject.keys.toList())
        assertEquals(1.0, progress.snapshot().num("totalGamesPlayed"))
    }

    @Test
    fun resetReadsBackEmptyOnANewSystem() = runTest {
        val progress = build()
        progress.init()
        progress.record("pickle", mapOf("score" to 40.0))
        progress.reset()
        val second = build()
        second.init()
        assertEquals(0.0, second.snapshot().num("xp"))
        assertEquals(0.0, second.snapshot().num("totalGamesPlayed"))
    }

    @Test
    fun anImportAfterResetMergesIntoTheEmptyDocument() = runTest {
        val source = build(LoggingStorage())
        source.init()
        source.addXp(120.0)
        val progress = build()
        progress.init()
        progress.addXp(900.0)
        progress.reset()
        progress.import(source.export(), silent = true)
        assertEquals(120.0, progress.snapshot().num("xp"))
    }

    @Test
    fun resetKeepsTheResetStateWhenTheWriteFails() = runTest {
        val progress = build()
        progress.init()
        progress.addXp(10.0)
        events.clear()
        storage.failSet = { true }
        val after = progress.reset()
        assertEquals(1, events.count { it is ProgressEvent.SaveFailed })
        assertEquals(0.0, after.num("xp"))
    }

    @Test
    fun resetBeforeInitThrows() = runTest {
        assertFailsWith<IllegalStateException> { build().reset() }
    }

    // Guards (ported from RewardSystemGuards)

    @Test
    fun everyMethodThrowsIllegalStateBeforeInit() = runTest {
        val progress = build()
        assertFailsWith<IllegalStateException> { progress.addXp(1.0) }
        assertFailsWith<IllegalStateException> { progress.record("tap") }
        assertFailsWith<IllegalStateException> { progress.snapshot() }
        assertFailsWith<IllegalStateException> { progress.export() }
        assertFailsWith<IllegalStateException> { progress.unlockAchievement("first_game") }
        assertFailsWith<IllegalStateException> { progress.getXpForNextLevel() }
        assertFailsWith<IllegalStateException> { progress.import(JsonObject(emptyMap())) }
        assertFailsWith<IllegalStateException> { progress.getUnlockedAchievements() }
        assertFailsWith<IllegalStateException> { progress.getUnseenAchievements() }
        assertFailsWith<IllegalStateException> { progress.getStreakMultiplier() }
        assertFailsWith<IllegalStateException> { progress.checkAchievements() }
        assertFailsWith<IllegalStateException> { progress.markAchievementsSeen(listOf("first_game")) }
    }

    @Test
    fun addXpChecksTheAmountBeforeThePlayer() = runTest {
        val progress = build()
        assertFailsWith<ArgumentTypeError> { progress.addXpRaw(JsonPrimitive("10")) }
        assertFailsWith<ArgumentTypeError> { progress.addXpRaw(JsonNull) }
        assertFailsWith<ArgumentTypeError> { progress.addXpRaw(null) }
        assertFailsWith<ArgumentTypeError> { progress.addXp(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { progress.addXp(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalStateException> { progress.addXpRaw(JsonPrimitive(1)) }
    }

    @Test
    fun recordChecksThePlayerBeforeTheArguments() = runTest {
        val progress = build()
        assertFailsWith<IllegalStateException> { progress.recordRaw(JsonPrimitive("__proto__")) }
        progress.init()
        assertFailsWith<ArgumentTypeError> { progress.recordRaw(JsonPrimitive("__proto__")) }
        assertFailsWith<ArgumentTypeError> { progress.recordRaw(JsonPrimitive("")) }
        assertFailsWith<ArgumentTypeError> { progress.recordRaw(JsonPrimitive(5)) }
        assertFailsWith<ArgumentTypeError> { progress.recordRaw(null) }
        assertFailsWith<ArgumentTypeError> { progress.recordRaw(JsonPrimitive("tap"), JsonNull) }
        assertFailsWith<ArgumentTypeError> { progress.recordRaw(JsonPrimitive("tap"), JsonArray(emptyList())) }
        assertFailsWith<ArgumentTypeError> {
            progress.recordRaw(JsonPrimitive("tap"), JsonObject(mapOf("score" to JsonPrimitive("50"))))
        }
        assertFailsWith<ArgumentTypeError> {
            progress.recordRaw(JsonPrimitive("tap"), JsonObject(mapOf("score" to JsonNull)))
        }
        assertFailsWith<IllegalArgumentException> { progress.record("tap", mapOf("score" to Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { progress.record("constructor") }
        // Nothing was counted by any of them.
        assertEquals(0.0, progress.snapshot().num("totalGamesPlayed"))
        assertEquals(0.0, progress.snapshot().num("xp"))
    }

    @Test
    fun aMissingResultIsEmpty() = runTest {
        val progress = build()
        progress.init()
        assertTrue(progress.recordRaw(JsonPrimitive("tap")))
        assertEquals(1.0, progress.snapshot().num("totalGamesPlayed"))
    }

    @Test
    fun unsafeAchievementIdsDoNotUnlock() = runTest {
        val progress = build()
        progress.init()
        for (id in listOf("__proto__", "constructor", "prototype", "toString", "")) {
            assertFalse(progress.unlockAchievement(id), id)
        }
        assertFalse(progress.unlockAchievementRaw(JsonPrimitive(5)))
        assertFailsWith<IllegalArgumentException> {
            progress.defineAchievement("__proto__", AchievementDefinition("a", "b", "c", 1.0) { _, _, _ -> true })
        }
        assertFailsWith<IllegalArgumentException> {
            progress.defineAchievement("ok", AchievementDefinition("a", "b", "c", 1.0))
        }
    }

    @Test
    fun anAchievementPaysOnlyOnce() = runTest {
        val progress = build()
        progress.init()
        assertTrue(progress.unlockAchievement("first_game"))
        assertFalse(progress.unlockAchievement("first_game"))
        assertEquals(50.0, progress.snapshot().num("xp"))
    }

    @Test
    fun aNegativeScoreEarnsNothingExtraAndNeverTakesXpAway() = runTest {
        val progress = build()
        progress.init()
        progress.record("tap", mapOf("score" to -100.0))
        // 10 base XP and the 50 XP first_game reward.
        assertEquals(60.0, progress.snapshot().num("xp"))
    }

    @Test
    fun xpStopsAtZeroAndReportsWhatWasRemoved() = runTest {
        val progress = build()
        progress.init()
        progress.addXp(30.0)
        val result = progress.addXp(-500.0)
        assertEquals(-30.0, result.xpGained)
        assertEquals(0.0, progress.snapshot().num("xp"))
        assertEquals(1L, result.newLevel)
        assertEquals(30.0, progress.export().getValue("xpSpentBy").jsonObject.values.single().let { (it as JsonPrimitive).content.toDouble() })
    }

    @Test
    fun extraMetricsAddUpAndBadOnesAddNothing() = runTest {
        val progress = build()
        progress.init()
        progress.recordRaw(
            JsonPrimitive("tap"),
            JsonObject(
                mapOf(
                    "coins" to JsonPrimitive(3),
                    "bad" to JsonPrimitive("x"),
                    "neg" to JsonPrimitive(-4),
                    "plays" to JsonPrimitive(99),
                    "__proto__" to JsonPrimitive(1),
                ),
            ),
        )
        progress.record("tap", mapOf("coins" to 2.0, "inf" to Double.POSITIVE_INFINITY))
        val stats = progress.snapshot().getValue("stats").jsonObject.getValue("tap").jsonObject
        assertEquals(5.0, stats.num("coins"))
        assertEquals(0.0, stats.num("bad"))
        assertEquals(0.0, stats.num("neg"))
        assertEquals(0.0, stats.num("inf"))
        assertEquals(2.0, stats.num("plays"))
        assertFalse("__proto__" in stats)
    }

    @Test
    fun aBankedLevelIsKept() = runTest {
        storage.values["dopamine_player"] = """{"v":3,"level":6,"xpBy":{"d1":10}}"""
        val progress = build()
        progress.init()
        assertEquals(6.0, progress.snapshot().num("level"))
        val result = progress.addXp(1.0)
        assertFalse(result.leveledUp)
        assertEquals(6L, result.newLevel)
        assertEquals(0.0, progress.getXpForNextLevel().progress)
    }

    @Test
    fun aRejectedPersistenceOfAsyncStorageIsASaveFailed() = runTest {
        val progress = build()
        progress.init()
        storage.delayMillis = 5
        storage.failSet = { it == "dopamine_player" }
        events.clear()
        progress.addXp(5.0)
        assertEquals(listOf("save_failed", "xp_gained"), events.map { it.wireName })
    }

    // Cancellation

    @Test
    fun aCancelledFirstInitLeavesNothingSet() = runTest {
        storage.getDelay = { 100 }
        val progress = build()
        val job = launch { progress.init() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        storage.getDelay = { 0 }
        assertFailsWith<IllegalStateException> { progress.addXp(1.0) }
        assertFailsWith<IllegalStateException> { progress.snapshot() }
        assertEquals(emptyList(), log)
    }

    @Test
    fun aCancelDuringTheDeviceReadLeavesNothingSet() = runTest {
        storage.getDelay = { key -> if (key == "dopamine_device") 100 else 0 }
        val progress = build()
        val job = launch { progress.init() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        storage.getDelay = { 0 }
        assertFailsWith<IllegalStateException> { progress.addXp(1.0) }
        assertFailsWith<IllegalStateException> { progress.record("tap") }
        progress.init()
        assertEquals(1, storage.writes("player"))
    }

    @Test
    fun aRecordCancelledInsideAHookStillPersists() = runTest {
        val progress = build()
        progress.init()
        progress.use(object : Middleware {
            override suspend fun afterXp(context: AfterXpContext) {
                delay(100)
            }
        })
        storage.delayMillis = 5
        storage.log.clear()
        val job = launch { progress.record("tap", mapOf("score" to 5.0)) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, storage.writes("player"))
        val saved = Json.parseToJsonElement(storage.values.getValue("dopamine_player")).jsonObject
        assertEquals(1.0, saved.getValue("gamesBy").jsonObject.values.single().let { (it as JsonPrimitive).content.toDouble() })
    }

    @Test
    fun aCancelledSecondInitStillSaves() = runTest {
        val progress = build()
        progress.init()
        storage.getDelay = { key -> if (key == "dopamine_player") 100 else 0 }
        val job = launch { progress.init() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        storage.getDelay = { 0 }
        storage.log.clear()
        progress.addXp(10.0)
        assertEquals(1, storage.writes("player"))
        assertFalse(progress.loadFailed)
    }
}
