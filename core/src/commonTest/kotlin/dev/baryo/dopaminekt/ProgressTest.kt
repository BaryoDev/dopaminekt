package dev.baryo.dopaminekt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressTest {
    private class Env(config: (TickClock, MutableList<Throwable>) -> ProgressConfigArgs = { _, _ -> ProgressConfigArgs() }) {
        val log = mutableListOf<String>()
        val events = mutableListOf<ProgressEvent>()
        val storage = LoggingStorage(log)
        val clock = TickClock()
        val errors = mutableListOf<Throwable>()
        val progress: Progress

        init {
            val args = config(clock, errors)
            progress = Progress(
                storage,
                ProgressConfig(
                    achievements = args.achievements,
                    builtinAchievements = args.builtin,
                    clock = clock,
                    streakDays = args.streakDays,
                    onError = { errors += it },
                ),
            )
            progress.recordInto(log, events)
        }

        fun logging(): Middleware = object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> {
                log += "beforeXp"
                return Verdict.Continue
            }
            override suspend fun afterXp(context: AfterXpContext) { log += "afterXp" }
            override suspend fun beforeRecord(context: BeforeRecordContext): Verdict<BeforeRecordContext> {
                log += "beforeRecord"
                return Verdict.Continue
            }
            override suspend fun afterRecord(context: AfterRecordContext) { log += "afterRecord" }
            override suspend fun beforeUnlock(context: BeforeUnlockContext): Verdict<BeforeUnlockContext> {
                log += "beforeUnlock"
                return Verdict.Continue
            }
            override suspend fun afterUnlock(context: AfterUnlockContext) { log += "afterUnlock" }
        }
    }

    private class ProgressConfigArgs(
        val achievements: Map<String, AchievementDefinition> = emptyMap(),
        val builtin: Boolean = true,
        val streakDays: StreakDays = StreakDays.Any,
    )

    @Test
    fun addXpWritesBeforeItAnnounces() = runTest {
        val env = Env()
        env.progress.init()
        env.progress.use(env.logging())
        env.log.clear()
        val result = env.progress.addXp(100.0, "bonus")
        assertEquals(AddXpResult(true, 2, 100.0), result)
        assertEquals(listOf("beforeXp", "set:player", "xp_gained", "level_up", "afterXp"), env.log)
        assertEquals(ProgressEvent.XpGained(100.0, "bonus", true, 2, 100.0), env.events[0])
        assertEquals(ProgressEvent.LevelUp(1, 2, 100.0), env.events[1])
    }

    @Test
    fun aFailedWriteGivesSaveFailedThenXpGained() = runTest {
        val env = Env()
        env.progress.init()
        env.storage.failSet = { it == "dopamine_player" }
        env.log.clear()
        env.events.clear()
        env.progress.addXp(10.0, "x")
        assertEquals(listOf("save_failed", "xp_gained"), env.log)
        assertEquals(1, env.errors.size)
    }

    @Test
    fun recordWritesOnceAfterItsEventsAndBeforeAfterRecord() = runTest {
        val env = Env()
        env.progress.init()
        env.progress.use(env.logging())
        env.log.clear()
        assertTrue(env.progress.record("tap", mapOf("score" to 50.0, "coins" to 3.0)))
        assertEquals(
            listOf(
                "beforeRecord", "personal_best", "beforeXp", "xp_gained", "afterXp",
                "beforeUnlock", "xp_gained", "achievement_unlocked", "afterUnlock",
                "set:player", "afterRecord",
            ),
            env.log,
        )
        assertEquals(1, env.storage.writes("player"))
        val first = env.events.filterIsInstance<ProgressEvent.XpGained>()
        assertEquals(20.0, first[0].amount)
        assertEquals("Played tap", first[0].reason)
        assertEquals("Achievement: First Steps", first[1].reason)
        assertEquals(50.0, first[1].amount)
    }

    @Test
    fun aDirectUnlockWritesTwiceInTheJsOrder() = runTest {
        val env = Env()
        env.progress.init()
        env.progress.use(env.logging())
        env.log.clear()
        assertTrue(env.progress.unlockAchievement("first_game"))
        assertEquals(
            listOf("beforeUnlock", "set:player", "xp_gained", "achievement_unlocked", "set:player", "afterUnlock"),
            env.log,
        )
        assertFalse(env.progress.unlockAchievement("first_game"))
        assertFalse(env.progress.unlockAchievement("nope"))
    }

    @Test
    fun anAchievementRewardWithNoGuardStillFiresXpGainedForZero() = runTest {
        val env = Env { _, _ ->
            ProgressConfigArgs(achievements = mapOf("free" to AchievementDefinition("Free", "d", "i", 0.0) { _, _, _ -> false }))
        }
        env.progress.init()
        env.events.clear()
        env.progress.unlockAchievement("free")
        val gained = env.events.filterIsInstance<ProgressEvent.XpGained>().single()
        assertEquals(0.0, gained.amount)
    }

    @Test
    fun importAnnouncesXpLevelAndNewAchievementsInOrder() = runTest {
        val source = Env()
        source.progress.init()
        source.progress.addXp(120.0)
        source.progress.unlockAchievement("first_game")
        val exported = source.progress.export()

        val target = Env()
        target.progress.init()
        target.events.clear()
        target.log.clear()
        target.progress.import(exported)
        assertEquals(listOf("set:player", "xp_gained", "level_up", "achievement_unlocked"), target.log)
        val gained = target.events[0] as ProgressEvent.XpGained
        assertEquals("import", gained.reason)
        assertEquals(170.0, gained.amount)
        assertEquals(170.0, gained.total)
        assertEquals(ProgressEvent.LevelUp(1, 2, 170.0), target.events[1])
        assertEquals("first_game", (target.events[2] as ProgressEvent.AchievementUnlocked).id)
    }

    @Test
    fun aSilentImportAnnouncesNothing() = runTest {
        val source = Env()
        source.progress.init()
        source.progress.addXp(120.0)
        val target = Env()
        target.progress.init()
        target.events.clear()
        val after = target.progress.import(source.progress.export(), silent = true)
        assertEquals(120.0, after.num("xp"))
        assertEquals(emptyList(), target.events)
    }

    @Test
    fun anImportedAchievementWithNoDefinitionFiresNothing() = runTest {
        val source = Env { _, _ ->
            ProgressConfigArgs(achievements = mapOf("custom" to AchievementDefinition("C", "d", "i", 5.0) { _, _, _ -> false }))
        }
        source.progress.init()
        source.progress.unlockAchievement("custom")
        val target = Env()
        target.progress.init()
        target.events.clear()
        target.progress.import(source.progress.export())
        assertEquals(listOf("xp_gained"), target.events.map { it.wireName })
        assertTrue("custom" in target.progress.export().getValue("achievements").jsonObject)
    }

    @Test
    fun resetPublishesAfterTheWriteAndWritesOnce() = runTest {
        val env = Env()
        env.progress.init()
        env.progress.addXp(10.0)
        env.log.clear()
        val after = env.progress.reset()
        assertEquals(listOf("set:player", "player_reset"), env.log)
        assertEquals(0.0, after.num("xp"))
        assertEquals(ProgressEvent.PlayerReset, env.events.last())
    }

    @Test
    fun resetFromAHookDefersItsWriteToTheEndOfTheRecord() = runTest {
        val env = Env()
        env.storage.delayMillis = 25
        env.progress.init()
        env.progress.use(object : Middleware {
            override suspend fun afterXp(context: AfterXpContext) {
                env.progress.reset()
            }
        })
        env.log.clear()
        env.progress.record("tap", mapOf("score" to 5.0))
        assertEquals(1, env.storage.writes("player"))
        assertEquals(1, env.log.count { it == "player_reset" })
        // The write lands after the reset ran inside the record.
        assertTrue(env.log.indexOf("player_reset") < env.log.indexOf("set:player"))
        assertEquals(0.0, env.progress.snapshot().num("totalGamesPlayed"))
    }

    @Test
    fun aHookThatAddsXpInsideARecordStaysBatched() = runTest {
        val env = Env()
        env.storage.delayMillis = 10
        env.progress.init()
        env.progress.use(object : Middleware {
            override suspend fun afterRecord(context: AfterRecordContext) {}
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> {
                return Verdict.Continue
            }
            override suspend fun beforeUnlock(context: BeforeUnlockContext): Verdict<BeforeUnlockContext> {
                env.progress.addXp(1.0, "hook")
                return Verdict.Continue
            }
        })
        env.log.clear()
        env.progress.record("tap", mapOf("score" to 5.0))
        assertEquals(1, env.storage.writes("player"))
    }

    @Test
    fun streakUpdatedFiresOnANewDayOnly() = runTest {
        val env = Env()
        env.progress.init()
        env.progress.record("tap")
        assertFalse(env.events.any { it is ProgressEvent.StreakUpdated })
        env.progress.record("tap")
        assertFalse(env.events.any { it is ProgressEvent.StreakUpdated })
        env.clock.day = "2026-08-08"
        env.progress.record("tap")
        env.progress.record("tap")
        assertEquals(listOf(ProgressEvent.StreakUpdated(2, 2)), env.events.filterIsInstance<ProgressEvent.StreakUpdated>())
    }

    @Test
    fun streakDaysRecordIgnoresAddXp() = runTest {
        val env = Env { _, _ -> ProgressConfigArgs(streakDays = StreakDays.Record) }
        env.progress.init()
        env.progress.addXp(10.0)
        assertEquals(0.0, env.progress.snapshot().getValue("streak").jsonObject.num("current"))
        env.progress.record("tap")
        assertEquals(1.0, env.progress.snapshot().getValue("streak").jsonObject.num("current"))
    }

    @Test
    fun aThrowingCheckIsReportedAndSkipped() = runTest {
        val env = Env { _, _ ->
            ProgressConfigArgs(
                builtin = false,
                achievements = mapOf(
                    "bad" to AchievementDefinition("Bad", "d", "i", 1.0) { _, _, _ -> throw IllegalStateException("boom") },
                    "good" to AchievementDefinition("Good", "d", "i", 1.0) { _, _, _ -> true },
                ),
            )
        }
        env.progress.init()
        env.progress.record("tap")
        assertEquals(1, env.errors.size)
        assertEquals(listOf("good"), env.progress.getUnlockedAchievements().map { (it["id"] as JsonPrimitive).content })
    }

    @Test
    fun builtinAchievementsOffDefinesNone() = runTest {
        val env = Env { _, _ -> ProgressConfigArgs(builtin = false) }
        env.progress.init()
        env.progress.record("tap")
        assertEquals(emptyList(), env.progress.getUnlockedAchievements())
        assertFalse(env.progress.unlockAchievement("first_game"))
    }

    @Test
    fun unlockedAchievementsMergeTheDefinitionWithTheStoredEntry() = runTest {
        val env = Env()
        env.progress.init()
        env.progress.unlockAchievement("first_game")
        val entry = env.progress.getUnlockedAchievements().single()
        assertEquals("First Steps", (entry["name"] as JsonPrimitive).content)
        assertEquals("first_game", (entry["id"] as JsonPrimitive).content)
        assertEquals(50.0, entry.num("reward"))
        assertEquals(1, env.progress.getUnseenAchievements().size)
        env.progress.markAchievementsSeen(listOf("first_game", "missing"))
        assertEquals(0, env.progress.getUnseenAchievements().size)
    }

    @Test
    fun middlewareCanRejectOrChangeTheAmount() = runTest {
        val env = Env()
        env.progress.init()
        val off = env.progress.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext) = Verdict.Patch(context.copy(amount = context.amount * 2))
        })
        assertEquals(20.0, env.progress.addXp(10.0).xpGained)
        off()
        env.progress.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> = Verdict.Reject
            override suspend fun beforeRecord(context: BeforeRecordContext): Verdict<BeforeRecordContext> = Verdict.Reject
        })
        assertEquals(AddXpResult(false, 1, 0.0), env.progress.addXp(10.0))
        assertFalse(env.progress.record("tap"))
        assertEquals(20.0, env.progress.snapshot().num("xp"))
    }

    @Test
    fun getXpForNextLevelReportsTheBand() = runTest {
        val env = Env()
        env.progress.init()
        env.progress.addXp(50.0)
        assertEquals(XpProgress(100.0, 50.0, 0.5), env.progress.getXpForNextLevel())
    }

    @Test
    fun aSnapshotIsNotChangedByLaterWork() = runTest {
        val env = Env()
        env.progress.init()
        val before = env.progress.snapshot()
        val exported = env.progress.export()
        env.progress.addXp(500.0)
        assertEquals(0.0, before.num("xp"))
        assertEquals(JsonObject(emptyMap()), exported.getValue("xpBy"))
    }
}
