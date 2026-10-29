package dev.baryo.dopaminekt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AchievementsTest {
    private fun player(games: Int = 0, level: Int = 1, streak: Int = 0) = JsonObject(
        mapOf(
            "totalGamesPlayed" to JsonPrimitive(games),
            "level" to JsonPrimitive(level),
            "streak" to JsonObject(mapOf("current" to JsonPrimitive(streak))),
        ),
    )

    private fun met(id: String, player: JsonObject): Boolean =
        assertNotNull(builtinAchievements().getValue(id).check)(player, null, null)

    @Test
    fun theFiveBuiltInsHaveTheJsFields() {
        val all = builtinAchievements()
        assertEquals(listOf("first_game", "level_5", "level_10", "streak_3", "streak_7"), all.keys.toList())
        val first = all.getValue("first_game")
        assertEquals("First Steps", first.name)
        assertEquals("Play your first game", first.description)
        assertEquals("\uD83C\uDFAE", first.icon)
        assertEquals(50.0, first.xp)
        assertEquals("Unstoppable", all.getValue("streak_7").name)
        assertEquals(300.0, all.getValue("streak_7").xp)
    }

    @Test
    fun checksUseTheirThresholds() {
        assertFalse(met("first_game", player(games = 0)))
        assertTrue(met("first_game", player(games = 1)))
        assertFalse(met("level_5", player(level = 4)))
        assertTrue(met("level_5", player(level = 5)))
        assertFalse(met("level_10", player(level = 9)))
        assertTrue(met("level_10", player(level = 10)))
        assertFalse(met("streak_3", player(streak = 2)))
        assertTrue(met("streak_3", player(streak = 3)))
        assertFalse(met("streak_7", player(streak = 6)))
        assertTrue(met("streak_7", player(streak = 7)))
    }

    @Test
    fun aViewMissingTheFieldsIsNotMet() {
        for (id in builtinAchievements().keys) assertFalse(met(id, JsonObject(emptyMap())), id)
    }

    @Test
    fun builtInRewardsMatchTheDefinitions() {
        assertEquals(BUILTIN_REWARDS, rewardsOf(builtinAchievements()))
    }

    @Test
    fun rewardsOfPaysZeroForBadXp() {
        val defs = mapOf(
            "ok" to AchievementDefinition("a", "b", "c", 25.0),
            "zero" to AchievementDefinition("a", "b", "c", 0.0),
            "negative" to AchievementDefinition("a", "b", "c", -5.0),
            "nan" to AchievementDefinition("a", "b", "c", Double.NaN),
            "inf" to AchievementDefinition("a", "b", "c", Double.POSITIVE_INFINITY),
        )
        assertEquals(mapOf("ok" to 25.0, "zero" to 0.0, "negative" to 0.0, "nan" to 0.0, "inf" to 0.0), rewardsOf(defs))
    }

    @Test
    fun aDefinitionWithoutACheckHasNone() {
        assertEquals(null, AchievementDefinition("a", "b", "c", 1.0).check)
    }
}
