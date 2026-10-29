package dev.baryo.dopaminekt

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

private fun valid(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.values.all(::valid)
    is JsonArray -> element.all(::valid)
    is JsonNull -> true
    is JsonPrimitive -> element.isString || element.content.let { it == "true" || it == "false" || NUMBER.matches(it) }
}

/**
 * Parses [text] the way JavaScript JSON.parse does. The kotlinx parser also reads bare words,
 * NaN, Infinity, single quotes, leading zeros, a plus sign and .5, so the parsed tree is checked
 * and any unquoted value that is not true, false, null or a JSON number is refused. 1e999 is valid
 * and reads as Infinity, as it does in JavaScript.
 *
 * @throws SerializationException when [text] is not valid JSON.
 */
internal fun parseJsonStrict(text: String): JsonElement {
    val element = Json.parseToJsonElement(text)
    if (!valid(element)) throw SerializationException("not valid JSON")
    return element
}
