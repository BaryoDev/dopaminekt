package dev.baryo.dopaminekt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.max
import kotlin.math.min

internal const val VERSION: Int = 3
internal const val MAX_DEVICES: Int = 20
internal const val LEGACY: String = "legacy"

/** Reward XP of the built-in achievements, used to read a 2.x document when the caller gives none. */
internal val BUILTIN_REWARDS: Map<String, Double> = mapOf(
    "first_game" to 50.0,
    "level_5" to 100.0,
    "level_10" to 250.0,
    "streak_3" to 100.0,
    "streak_7" to 300.0,
)

internal class Achievement(val unlockedAt: Double, val seen: Boolean, val reward: Double)

/** A version 3 document. Counts are Long, amounts are Double, all capped at 2^53 - 1. */
internal class Doc(
    var name: String = "Player",
    var createdAt: Double = 0.0,
    var lastPlayedAt: Double = 0.0,
    var level: Long = 1,
    var longest: Long = 0,
    var playDates: List<String> = emptyList(),
    val xpBy: MutableMap<String, Double> = LinkedHashMap(),
    val xpSpentBy: MutableMap<String, Double> = LinkedHashMap(),
    val gamesBy: MutableMap<String, Long> = LinkedHashMap(),
    val playsBy: MutableMap<String, MutableMap<String, Long>> = LinkedHashMap(),
    val best: MutableMap<String, Double> = LinkedHashMap(),
    val sumsBy: MutableMap<String, MutableMap<String, MutableMap<String, Double>>> = LinkedHashMap(),
    var achievements: MutableMap<String, Achievement> = LinkedHashMap(),
)

internal fun emptyDoc(now: Double = 0.0): Doc = Doc(createdAt = now, lastPlayedAt = now)

// Summed in key order, so the same document always gives the same float.
internal fun <T> sum(map: Map<String, T>, pick: (T) -> Double): Double =
    map.keys.sorted().fold(0.0) { total, key -> capDouble(total + pick(map.getValue(key))) }

private fun sumAmounts(map: Map<String, Double>): Double = sum(map) { it }
private fun sumCounts(map: Map<String, Long>): Double = sum(map) { it.toDouble() }

private fun count(value: JsonElement?, fallback: Long, min: Long = 0): Long {
    val whole = value.wholeNumber() ?: return fallback
    return if (whole >= min) capLong(whole) else fallback
}

private fun objectOrNull(value: JsonElement?): JsonObject? = value as? JsonObject

private fun pickAmounts(source: JsonElement?): MutableMap<String, Double> {
    val map = LinkedHashMap<String, Double>()
    for ((key, value) in objectOrNull(source) ?: return map) {
        val n = value.finiteNumber()
        if (isSafeKey(key) && n != null && n >= 0) map[key] = capDouble(n)
    }
    return map
}

private fun pickCounts(source: JsonElement?): MutableMap<String, Long> {
    val map = LinkedHashMap<String, Long>()
    for ((key, value) in objectOrNull(source) ?: return map) {
        val n = value.wholeNumber()
        if (isSafeKey(key) && n != null && n >= 0) map[key] = capLong(n)
    }
    return map
}

private fun pickAnyNumbers(source: JsonElement?): MutableMap<String, Double> {
    val map = LinkedHashMap<String, Double>()
    for ((key, value) in objectOrNull(source) ?: return map) {
        val n = value.finiteNumber()
        if (isSafeKey(key) && n != null) map[key] = capDouble(n)
    }
    return map
}

private fun pickNestedCounts(source: JsonElement?): MutableMap<String, MutableMap<String, Long>> {
    val map = LinkedHashMap<String, MutableMap<String, Long>>()
    for ((key, value) in objectOrNull(source) ?: return map) {
        if (isSafeKey(key)) map[key] = pickCounts(value)
    }
    return map
}

private fun pickNestedSums(source: JsonElement?): MutableMap<String, MutableMap<String, MutableMap<String, Double>>> {
    val map = LinkedHashMap<String, MutableMap<String, MutableMap<String, Double>>>()
    for ((activity, value) in objectOrNull(source) ?: return map) {
        if (!isSafeKey(activity)) continue
        val inner = LinkedHashMap<String, MutableMap<String, Double>>()
        for ((key, devices) in objectOrNull(value) ?: JsonObject(emptyMap())) {
            if (isSafeKey(key)) inner[key] = pickAnyNumbers(devices)
        }
        map[activity] = inner
    }
    return map
}

private fun pickAchievements(source: JsonElement?, now: Double): MutableMap<String, Achievement> {
    val map = LinkedHashMap<String, Achievement>()
    for ((id, value) in objectOrNull(source) ?: return map) {
        val entry = objectOrNull(value)
        if (!isSafeKey(id) || entry == null) continue
        val reward = entry["reward"].finiteNumber()
        map[id] = Achievement(
            unlockedAt = entry["unlockedAt"].finiteNumber() ?: now,
            seen = (entry["seen"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true,
            reward = if (reward != null && reward >= 0) capDouble(reward) else 0.0,
        )
    }
    return map
}

private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

// Every activity has a play map, a best and a sum map, so merge never has to ask whether one exists.
private fun finish(doc: Doc): Doc {
    val activities = LinkedHashSet<String>().apply {
        addAll(doc.playsBy.keys)
        addAll(doc.best.keys)
        addAll(doc.sumsBy.keys)
    }
    for (activity in activities) {
        if (activity !in doc.playsBy) doc.playsBy[activity] = LinkedHashMap()
        if (activity !in doc.best) doc.best[activity] = 0.0
        if (activity !in doc.sumsBy) doc.sumsBy[activity] = LinkedHashMap()
    }
    return doc
}

internal fun read3(input: JsonObject, now: Double, today: String?): Doc {
    val days = (input["playDates"] as? JsonArray)?.mapNotNull { text(it) }?.filter { isDay(it) } ?: emptyList()
    return finish(
        Doc(
            name = text(input["name"]) ?: "Player",
            createdAt = input["createdAt"].finiteNumber() ?: now,
            lastPlayedAt = input["lastPlayedAt"].finiteNumber() ?: now,
            level = count(input["level"], 1, 1),
            longest = count(input["longest"], 0),
            playDates = trimDays(days, today),
            xpBy = pickAmounts(input["xpBy"]),
            xpSpentBy = pickAmounts(input["xpSpentBy"]),
            gamesBy = pickCounts(input["gamesBy"]),
            playsBy = pickNestedCounts(input["playsBy"]),
            best = pickAmounts(input["best"]),
            sumsBy = pickNestedSums(input["sumsBy"]),
            achievements = pickAchievements(input["achievements"], now),
        ),
    )
}

// A 2.x document, moved under the fixed legacy key. Two devices that read the
// same save get the same entries, so they merge by max and nothing doubles.
internal fun read2(input: JsonObject, now: Double, rewards: Map<String, Double>, today: String?): Doc {
    val doc = emptyDoc(now)
    doc.name = text(input["name"]) ?: "Player"
    doc.createdAt = input["createdAt"].finiteNumber() ?: now
    doc.lastPlayedAt = input["lastPlayedAt"].finiteNumber() ?: now
    doc.achievements = pickAchievements(input["achievements"], now)

    var granted = 0.0
    val granted2 = LinkedHashMap<String, Achievement>()
    for ((id, entry) in doc.achievements) {
        val given = rewards[id]
        val reward = if (given != null && given.isFinite() && given >= 0) capDouble(given) else 0.0
        granted2[id] = Achievement(entry.unlockedAt, entry.seen, reward)
        granted += reward
    }
    doc.achievements = granted2

    // The old xp already holds the rewards of the unlocked achievements.
    val xpValue = input["xp"].finiteNumber()
    val xp = if (xpValue != null && xpValue >= 0) capDouble(xpValue) else 0.0
    val own = max(0.0, xp - granted)
    if (own > 0) doc.xpBy[LEGACY] = own
    doc.level = max(levelFor(xp), count(input["level"], 1, 1))
    val games = count(input["totalGamesPlayed"], 0)
    if (games > 0) doc.gamesBy[LEGACY] = games

    val stats = objectOrNull(input["stats"])
    if (stats != null) {
        for ((activity, value) in stats) {
            val entry = objectOrNull(value)
            if (!isSafeKey(activity) || entry == null) continue
            val plays = count(entry["totalPlays"], 0)
            doc.playsBy[activity] = if (plays > 0) linkedMapOf(LEGACY to plays) else LinkedHashMap()
            val high = entry["highScore"].finiteNumber()
            doc.best[activity] = if (high != null && high >= 0) capDouble(high) else 0.0
            val sums = LinkedHashMap<String, MutableMap<String, Double>>()
            doc.sumsBy[activity] = sums
            for ((key, v) in entry) {
                val n = v.finiteNumber()
                if (isSafeKey(key) && key != "totalPlays" && key != "highScore" && n != null) {
                    sums[key] = linkedMapOf(LEGACY to capDouble(n))
                }
            }
        }
    }

    val streak = objectOrNull(input["streak"]) ?: JsonObject(emptyMap())
    val current = count(streak["current"], 1, 1)
    doc.longest = max(current, count(streak["longest"], 0))
    val last = text(streak["lastPlayDate"])
    if (last != null && isDay(last)) {
        val days = ArrayList<String>()
        for (back in 0 until min(current, 400L).toInt()) days.add(shiftDay(last, -back))
        doc.playDates = trimDays(days, today)
    } else {
        doc.longest = count(streak["longest"], 0)
    }

    return finish(doc)
}

internal fun deviceIds(doc: Doc): Set<String> {
    val ids = LinkedHashSet<String>()
    ids.addAll(doc.xpBy.keys)
    ids.addAll(doc.xpSpentBy.keys)
    ids.addAll(doc.gamesBy.keys)
    for (devices in doc.playsBy.values) ids.addAll(devices.keys)
    for (keys in doc.sumsBy.values) for (devices in keys.values) ids.addAll(devices.keys)
    return ids
}

internal fun checkDevices(doc: Doc) {
    if (deviceIds(doc).size > MAX_DEVICES) {
        throw DopamineError("too_many_devices", "a save holds at most $MAX_DEVICES devices")
    }
}

internal fun xpOf(doc: Doc): Double =
    capDouble(max(0.0, sumAmounts(doc.xpBy) + sum(doc.achievements) { it.reward } - sumAmounts(doc.xpSpentBy)))

internal fun levelOf(doc: Doc): Long = max(doc.level, levelFor(xpOf(doc)))

private fun amounts(map: Map<String, Double>): JsonObject = JsonObject(map.mapValues { number(it.value) })

private fun counts(map: Map<String, Long>): JsonObject = JsonObject(map.mapValues { JsonPrimitive(it.value) })

/** Renders a document as a plain JSON object, whole numbers written as integers. */
internal fun render(doc: Doc): JsonObject = JsonObject(
    linkedMapOf(
        "v" to JsonPrimitive(VERSION),
        "name" to JsonPrimitive(doc.name),
        "createdAt" to number(doc.createdAt),
        "lastPlayedAt" to number(doc.lastPlayedAt),
        "level" to JsonPrimitive(doc.level),
        "longest" to JsonPrimitive(doc.longest),
        "playDates" to JsonArray(doc.playDates.map { JsonPrimitive(it) }),
        "xpBy" to amounts(doc.xpBy),
        "xpSpentBy" to amounts(doc.xpSpentBy),
        "gamesBy" to counts(doc.gamesBy),
        "playsBy" to JsonObject(doc.playsBy.mapValues { counts(it.value) }),
        "best" to amounts(doc.best),
        "sumsBy" to JsonObject(doc.sumsBy.mapValues { (_, keys) -> JsonObject(keys.mapValues { amounts(it.value) }) }),
        "achievements" to JsonObject(
            doc.achievements.mapValues { (_, a) ->
                JsonObject(
                    linkedMapOf(
                        "unlockedAt" to number(a.unlockedAt),
                        "seen" to JsonPrimitive(a.seen),
                        "reward" to number(a.reward),
                    ),
                )
            },
        ),
    ),
)

/** The derived view of a document as of [today] (a local YYYY-MM-DD day). */
internal fun view(doc: Doc, today: String): JsonObject {
    val stats = LinkedHashMap<String, JsonElement>()
    for (activity in doc.playsBy.keys) {
        val entry = LinkedHashMap<String, JsonElement>()
        entry["plays"] = number(sumCounts(doc.playsBy.getValue(activity)))
        entry["best"] = number(doc.best[activity] ?: 0.0)
        for ((key, devices) in doc.sumsBy[activity] ?: emptyMap()) {
            if (key != "plays" && key != "best") entry[key] = number(sumAmounts(devices))
        }
        stats[activity] = JsonObject(entry)
    }

    val achievements = LinkedHashMap<String, JsonElement>()
    for ((id, a) in doc.achievements) {
        achievements[id] = JsonObject(
            linkedMapOf(
                "unlockedAt" to number(a.unlockedAt),
                "seen" to JsonPrimitive(a.seen),
                "reward" to number(a.reward),
            ),
        )
    }

    val streak = LinkedHashMap<String, JsonElement>()
    streak["current"] = JsonPrimitive(currentStreak(doc.playDates, today))
    streak["longest"] = JsonPrimitive(max(doc.longest, longestRun(doc.playDates).toLong()))
    lastDayUntil(doc.playDates, today)?.let { streak["lastPlayDate"] = JsonPrimitive(it) }

    return JsonObject(
        linkedMapOf(
            "name" to JsonPrimitive(doc.name),
            "xp" to number(xpOf(doc)),
            "level" to JsonPrimitive(levelOf(doc)),
            "totalGamesPlayed" to number(sumCounts(doc.gamesBy)),
            "createdAt" to number(doc.createdAt),
            "lastPlayedAt" to number(doc.lastPlayedAt),
            "streak" to JsonObject(streak),
            "achievements" to JsonObject(achievements),
            "stats" to JsonObject(stats),
        ),
    )
}
