package dev.baryo.dopaminekt

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.math.min

// Joins two maps by taking the larger number at each key. Max per key is what
// makes the join independent of order.
private fun <T : Comparable<T>> joinMax(a: Map<String, T>, b: Map<String, T>): MutableMap<String, T> {
    val out = LinkedHashMap<String, T>()
    for (key in a.keys + b.keys) {
        val x = a[key]
        val y = b[key]
        out[key] = if (x == null) y!! else if (y == null) x else if (x >= y) x else y
    }
    return out
}

private fun <V> joinNested(
    a: Map<String, V>,
    b: Map<String, V>,
    inner: (V, V) -> V,
    empty: () -> V,
): MutableMap<String, V> {
    val out = LinkedHashMap<String, V>()
    for (key in a.keys + b.keys) out[key] = inner(a[key] ?: empty(), b[key] ?: empty())
    return out
}

private fun joinAchievements(a: Map<String, Achievement>, b: Map<String, Achievement>): MutableMap<String, Achievement> {
    val out = LinkedHashMap<String, Achievement>()
    for (id in a.keys + b.keys) {
        val x = a[id]
        val y = b[id]
        if (x == null || y == null) {
            out[id] = (x ?: y)!!
            continue
        }
        // The earliest unlock decides the reward; a tie takes the larger.
        val first = if (x.unlockedAt < y.unlockedAt || (x.unlockedAt == y.unlockedAt && x.reward >= y.reward)) x else y
        out[id] = Achievement(first.unlockedAt, x.seen || y.seen, first.reward)
    }
    return out
}

private fun positive(a: Double, b: Double): Double = if (a > 0 && b > 0) min(a, b) else max(a, b)

/**
 * Joins two saves. It reads no clock and no storage, and the result does not
 * depend on argument order, grouping, or merging twice.
 *
 * Throws [DopamineError] when either input is invalid or the result would hold
 * more than 20 devices. The other parameters are the same as [validate].
 */
public fun merge(
    a: JsonElement,
    b: JsonElement,
    now: Long = 0,
    today: String? = null,
    rewards: Map<String, Double>? = null,
): JsonObject {
    val x = parseDoc(a, now, today, rewards)
    val y = parseDoc(b, now, today, rewards)
    val later = if (x.lastPlayedAt != y.lastPlayedAt) {
        if (x.lastPlayedAt > y.lastPlayedAt) x else y
    } else {
        if (x.name >= y.name) x else y
    }
    val day = if (isDay(today)) today else null

    val doc = Doc(
        name = later.name,
        createdAt = positive(x.createdAt, y.createdAt),
        lastPlayedAt = max(x.lastPlayedAt, y.lastPlayedAt),
        level = max(x.level, y.level),
        longest = max(x.longest, y.longest),
        playDates = trimDays(x.playDates + y.playDates, day),
        xpBy = joinMax(x.xpBy, y.xpBy),
        xpSpentBy = joinMax(x.xpSpentBy, y.xpSpentBy),
        gamesBy = joinMax(x.gamesBy, y.gamesBy),
        playsBy = joinNested<MutableMap<String, Long>>(x.playsBy, y.playsBy, { p, q -> joinMax(p, q) }, { LinkedHashMap() }),
        best = joinMax(x.best, y.best),
        sumsBy = joinNested<MutableMap<String, MutableMap<String, Double>>>(
            x.sumsBy,
            y.sumsBy,
            { p, q -> joinNested<MutableMap<String, Double>>(p, q, { r, s -> joinMax(r, s) }, { LinkedHashMap() }) },
            { LinkedHashMap() },
        ),
        achievements = joinAchievements(x.achievements, y.achievements),
    )
    checkDevices(doc)
    return render(doc)
}
