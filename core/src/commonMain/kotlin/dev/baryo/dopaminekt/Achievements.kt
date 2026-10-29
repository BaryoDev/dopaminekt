package dev.baryo.dopaminekt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * An achievement an app defines. [xp] is paid once when it unlocks.
 *
 * [check] is asked after a play is recorded. It gets the player view, and the activity and result
 * of that play. It returns true to unlock. It is null for an achievement the app unlocks itself.
 * A check that throws counts as not met.
 */
public class AchievementDefinition(
    public val name: String,
    public val description: String,
    public val icon: String,
    public val xp: Double,
    public val check: ((player: JsonObject, activity: String?, result: JsonObject?) -> Boolean)? = null,
)

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject

/** The five achievements every player has unless the app turns them off. */
internal fun builtinAchievements(): Map<String, AchievementDefinition> = linkedMapOf(
    "first_game" to AchievementDefinition("First Steps", "Play your first game", "\uD83C\uDFAE", 50.0) { player, _, _ ->
        (player.number("totalGamesPlayed") ?: 0.0) >= 1
    },
    "level_5" to AchievementDefinition("Rising Star", "Reach level 5", "\u2B50", 100.0) { player, _, _ ->
        (player.number("level") ?: 0.0) >= 5
    },
    "level_10" to AchievementDefinition("Expert Player", "Reach level 10", "\uD83C\uDFC6", 250.0) { player, _, _ ->
        (player.number("level") ?: 0.0) >= 10
    },
    "streak_3" to AchievementDefinition("Dedicated", "Play 3 days in a row", "\uD83D\uDD25", 100.0) { player, _, _ ->
        (player.child("streak")?.number("current") ?: 0.0) >= 3
    },
    "streak_7" to AchievementDefinition("Unstoppable", "Play 7 days in a row", "\uD83D\uDCA5", 300.0) { player, _, _ ->
        (player.child("streak")?.number("current") ?: 0.0) >= 7
    },
)

/** The XP each definition pays. A missing, non finite or non positive xp pays 0. */
internal fun rewardsOf(achievements: Map<String, AchievementDefinition>): Map<String, Double> =
    achievements.mapValues { (_, definition) -> if (definition.xp.isFinite() && definition.xp > 0) definition.xp else 0.0 }
