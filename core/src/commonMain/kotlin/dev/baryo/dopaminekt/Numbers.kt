package dev.baryo.dopaminekt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
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

/**
 * Formats [value] the way a JavaScript engine prints a number: the shortest digits that read back
 * as the same double, plain decimal for 1e-6 <= |x| < 1e21, and d.ddde+NN or d.ddde-NN otherwise.
 */
internal fun jsNumber(value: Double): String {
    if (value == 0.0) return "0"
    require(value.isFinite()) { "not a finite number" }
    // Double.toString gives the shortest round-trip digits as I.F or I.FE<exp>.
    val text = abs(value).toString()
    val mantissa = text.substringBefore('E')
    val exponent = text.substringAfter('E', "0").toInt()
    val whole = mantissa.substringBefore('.')
    var digits = whole + mantissa.substringAfter('.', "")
    var n = whole.length + exponent
    val leading = digits.length - digits.trimStart('0').length
    digits = digits.trimStart('0').trimEnd('0')
    n -= leading
    val k = digits.length
    val sign = if (value < 0) "-" else ""
    val body = when {
        n in k..21 -> digits + "0".repeat(n - k)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val e = n - 1
            val tail = (if (e < 0) "e-" else "e+") + abs(e)
            if (k == 1) digits + tail else digits[0] + "." + digits.substring(1) + tail
        }
    }
    return sign + body
}

/** Renders whole numbers as integers (100, not 100.0), so exported text matches the other ports. */
@OptIn(ExperimentalSerializationApi::class)
internal fun number(value: Double): JsonPrimitive =
    if (value.isWhole() && abs(value) <= MAX_SAFE_DOUBLE) JsonPrimitive(value.toLong()) else JsonUnquotedLiteral(jsNumber(value))
