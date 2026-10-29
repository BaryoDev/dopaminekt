package dev.baryo.dopaminekt

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun parseDoc(input: JsonElement, now: Long, today: String?, rewards: Map<String, Double>?): Doc {
    if (input !is JsonObject) throw DopamineError("invalid_document", "a save must be an object")
    val version = input["v"]
    if (version != null && version.finiteNumber() != VERSION.toDouble()) {
        val shown = (version as? JsonPrimitive)?.content ?: version.toString()
        throw DopamineError("unsupported_version", "save version $shown is not $VERSION")
    }
    val day = if (isDay(today)) today else null
    val doc = if (version != null) {
        read3(input, now.toDouble(), day)
    } else {
        read2(input, now.toDouble(), rewards ?: BUILTIN_REWARDS, day)
    }
    checkDevices(doc)
    return doc
}

/**
 * Cleans any JSON value into a version 3 document, or throws [DopamineError].
 *
 * It reads no clock and no storage. A document with no `v` is a 2.x save and
 * is migrated. Entries that are not usable are dropped, not rejected.
 *
 * @param input the value to clean.
 * @param now fills timestamps that are missing, in epoch milliseconds.
 * @param today a local `YYYY-MM-DD` day. It sets the 400 day window for play dates.
 * @param rewards the XP a 2.x save already holds for each achievement id. The five
 * built-in achievements are used when this is null.
 */
public fun validate(
    input: JsonElement,
    now: Long = 0,
    today: String? = null,
    rewards: Map<String, Double>? = null,
): JsonObject = render(parseDoc(input, now, today, rewards))
