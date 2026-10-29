package dev.baryo.dopaminekt

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/** Largest count a document keeps, 2^53 - 1. */
internal const val MAX_SAFE: Long = 9_007_199_254_740_991L
private const val MAX_SAFE_DOUBLE: Double = 9_007_199_254_740_991.0

/** Keys that must stay plain data and never index a document. */
private val UNSAFE_KEYS = setOf("__proto__", "constructor", "prototype")

internal fun isSafeKey(key: String): Boolean = key.isNotEmpty() && key !in UNSAFE_KEYS

/** A finite number. Strings, booleans, null, arrays and objects are not numbers. */
internal fun JsonElement?.finiteNumber(): Double? {
    if (this !is JsonPrimitive || this is JsonNull || isString) return null
    val value = doubleOrNull ?: return null
    return if (value.isFinite()) value else null
}

/** An integer is a finite number with no fraction, so 2.0 and 1e3 pass and 2.5 does not. */
internal fun Double.isWhole(): Boolean = isFinite() && this == floor(this)

internal fun JsonElement?.wholeNumber(): Double? = finiteNumber()?.takeIf { it.isWhole() }

internal fun capDouble(value: Double): Double = if (value > MAX_SAFE_DOUBLE) MAX_SAFE_DOUBLE else value

/** A whole value as a Long, capped at 2^53 - 1. */
internal fun capLong(value: Double): Long = if (value >= MAX_SAFE_DOUBLE) MAX_SAFE else value.toLong()

/** Renders whole numbers as integers (100, not 100.0), so exported text matches the other ports. */
internal fun number(value: Double): JsonPrimitive =
    if (value.isWhole() && value > -9.0e18 && value < 9.0e18) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
