package dev.baryo.dopaminekt

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A clock the test moves. */
internal class TickClock(var day: String = "2026-08-07", var millis: Long = 1_000_000) : Clock {
    override fun now(): Long = millis
    override fun localDate(millis: Long): String = day
}

/**
 * A storage that writes into a shared [log] as `set:<key>`, so a test can see where writes fall
 * among events and hooks. It can delay or fail calls.
 */
internal class LoggingStorage(val log: MutableList<String> = mutableListOf()) : StoragePort {
    val values = LinkedHashMap<String, String>()
    var delayMillis = 0L
    var getDelay: (String) -> Long = { 0L }
    var failSet: (String) -> Boolean = { false }
    var failGet: (String) -> Boolean = { false }

    override suspend fun get(key: String): String? {
        getDelay(key).let { if (it > 0) delay(it) }
        if (failGet(key)) throw IllegalStateException("blocked")
        return values[key]
    }

    override suspend fun set(key: String, value: String) {
        if (delayMillis > 0) delay(delayMillis)
        if (failSet(key)) throw IllegalStateException("QuotaExceededError")
        log += "set:" + key.removePrefix("dopamine_")
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    fun writes(key: String): Int = log.count { it == "set:$key" }
}

/** Records the wire name of every event into [log]. */
internal fun Progress.recordInto(log: MutableList<String>, events: MutableList<ProgressEvent> = mutableListOf()) {
    for (name in listOf(
        "xp_gained", "level_up", "achievement_unlocked", "streak_updated", "personal_best",
        "player_reset", "save_failed", "load_failed",
    )) {
        on(name) {
            log += it.wireName
            events += it
        }
    }
}

internal fun JsonObject.num(key: String): Double = (getValue(key) as JsonPrimitive).doubleOrNull!!
