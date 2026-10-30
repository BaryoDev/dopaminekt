package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the language-neutral cases in spec/core/fixtures through [Progress], in two time zones.
 * Anything the runner does not understand fails the test.
 */
class FixtureRunnerTest {
    private val zones = listOf("Asia/Manila", "America/Los_Angeles")

    // Cases per file. A fixture change must update these. None is skipped.
    private val expectedCounts = mapOf(
        "achievements.json" to 7,
        "levels.json" to 6,
        "merge.json" to 27,
        "migrate.json" to 34,
        "scores.json" to 13,
        "streaks.json" to 18,
    )

    @Test
    fun fixtureRunnerRunsEveryCase() = runTest {
        val failures = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()
        assertTrue(FixtureData.files.isNotEmpty(), "no fixture files were generated")

        for ((file, text) in FixtureData.files.entries.sortedBy { it.key }.map { it.key to it.value }) {
            val suite = Json.parseToJsonElement(text) as JsonObject
            val cases = suite["cases"] as? JsonArray
            assertTrue(cases != null && cases.isNotEmpty(), "$file has no cases")
            var run = 0
            for (element in cases) {
                val case = element as JsonObject
                val name = (case["name"] as JsonPrimitive).content
                try {
                    FixtureCase.inspect(case)
                    run++
                    for (zone in zones) {
                        try {
                            FixtureCase(case, TimeZone.of(zone)).run()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            failures += "$file / $name / $zone: ${error.message}"
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failures += "$file / $name: ${error.message}"
                }
            }
            counts[file] = run
            println("fixture runner $file: $run run")
            if (run != cases.size) failures += "$file: only $run of ${cases.size} cases ran"
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(expectedCounts, counts, "cases run per file")
    }
}

private val CASE_KEYS = setOf("name", "note", "config", "clock", "device", "savedJson", "savedRaw", "afterInit", "steps", "storage")
private val STORAGE_OPTION_KEYS = setOf("failWrites")
private val CONFIG_KEYS = setOf("builtinAchievements", "achievements", "streakDays")
private val ACHIEVEMENT_KEYS = setOf("name", "description", "icon", "xp", "when")
private val EXPECT_KEYS = setOf(
    "player", "playerExact", "doc", "docExact", "absent", "achievements", "events",
    "returns", "returnsExact", "same", "throws", "code",
)
private val OPS: Map<String, Set<String>> = mapOf(
    "setClock" to setOf("at"),
    "reload" to setOf("device"),
    "addXP" to setOf("amount", "reason"),
    "record" to setOf("activity", "result"),
    "unlockAchievement" to setOf("id"),
    "markAchievementsSeen" to setOf("ids"),
    "reset" to setOf("overwrite"),
    "getXPForNextLevel" to emptySet(),
    "export" to setOf("as"),
    "import" to setOf("doc", "from", "silent"),
    "merge" to setOf("a", "b", "as"),
    "validate" to setOf("doc", "as"),
)
private val CLOCK_SHAPE = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$")

private fun assertKeys(where: String, obj: JsonObject, allowed: Set<String>) {
    for (key in obj.keys) if (key !in allowed) fail("$where: unknown key \"$key\"")
}

/** A real boolean. The strings "true" and "false" are not booleans. */
private fun JsonElement?.bool(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun getPath(root: JsonElement?, path: String): JsonElement? {
    var node = root
    for (part in path.split(".")) {
        node = (node as? JsonObject)?.get(part) ?: return null
    }
    return node
}

private fun primitiveEquals(a: JsonPrimitive, b: JsonPrimitive, strict: Boolean = false): Boolean {
    if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
    if (a.isString || b.isString) return a.isString && b.isString && a.content == b.content
    val x = a.finiteNumber()
    val y = b.finiteNumber()
    return if (x != null && y != null) (if (strict) x.toRawBits() == y.toRawBits() else x == y) else a.content == b.content
}

private fun deepEqual(a: JsonElement?, b: JsonElement?, strict: Boolean = false): Boolean = when {
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { deepEqual(a[it], b[it], strict) }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { deepEqual(a[it], b[it], strict) }
    a is JsonPrimitive && b is JsonPrimitive -> primitiveEquals(a, b, strict)
    else -> false
}

// Every key in `want` must be a key of `got` and match. Arrays match element by element and by length.
private fun matchSubset(got: JsonElement?, want: JsonElement, where: String) {
    when (want) {
        is JsonObject -> {
            if (got !is JsonObject) fail("$where: expected an object, got $got")
            for ((key, value) in want) {
                if (key !in got) fail("$where.$key: missing")
                matchSubset(got[key], value, "$where.$key")
            }
        }
        is JsonArray -> {
            if (got !is JsonArray || got.size != want.size) fail("$where: expected $want, got $got")
            want.forEachIndexed { i, item -> matchSubset(got[i], item, "$where[$i]") }
        }
        is JsonPrimitive -> if (got !is JsonPrimitive || !primitiveEquals(got, want)) fail("$where: expected $want, got $got")
    }
}

private fun nonEmpty(value: JsonElement, where: String) {
    val size = when (value) {
        is JsonArray -> value.size
        is JsonObject -> value.size
        else -> fail("$where: expected an object or array")
    }
    if (size == 0) fail("$where: empty, so it checks nothing")
}

/** A fixture condition is data. The runner checks its shape; no check function runs at load. */
private fun checkCondition(id: String, whenValue: JsonElement?) {
    val cond = whenValue as? JsonObject ?: fail("achievement $id: \"when\" is required")
    fun only(vararg allowed: String) = assertKeys("achievement $id.when", cond, allowed.toSet())
    when {
        "always" in cond -> only("always")
        "never" in cond -> only("never")
        "throws" in cond -> only("throws")
        "player" in cond -> {
            only("player", "gte")
            val path = cond["player"].string()
            if (path == null || cond["gte"].finiteNumber() == null) fail("achievement $id: \"player\" must be a path and \"gte\" a number")
            val probe = view(emptyDoc(0.0), "2026-01-01")
            if (getPath(probe, path).finiteNumber() == null) fail("achievement $id: \"$path\" is not a numeric player field")
        }
        "activity" in cond -> only("activity")
        "score_gte" in cond -> {
            only("score_gte")
            if (cond["score_gte"].finiteNumber() == null) fail("achievement $id: \"score_gte\" must be a number")
        }
        else -> fail("achievement $id: unknown condition ${cond.keys}")
    }
}

/** Builds the achievement a fixture describes. Its check is data, so the runner builds the function. */
private fun definitionOf(id: String, def: JsonObject): AchievementDefinition {
    val cond = def["when"] as JsonObject
    val check: (JsonObject, String?, JsonObject?) -> Boolean = when {
        "always" in cond -> { _, _, _ -> true }
        "never" in cond -> { _, _, _ -> false }
        "throws" in cond -> { _, _, _ -> throw IllegalStateException("check failed on purpose") }
        "player" in cond -> {
            val path = cond["player"].string()!!
            val gte = cond["gte"].finiteNumber()!!
            ({ player, _, _ -> (getPath(player, path).finiteNumber() ?: Double.NaN) >= gte })
        }
        "activity" in cond -> {
            val want = cond["activity"].string()
            ({ _, activity, _ -> activity == want })
        }
        else -> {
            val min = cond["score_gte"].finiteNumber()!!
            ({ _, _, result -> (result?.get("score").finiteNumber() ?: Double.NaN) >= min })
        }
    }
    return AchievementDefinition(
        name = if ("name" in def) def["name"].string()!! else id,
        description = if ("description" in def) def["description"].string()!! else "",
        icon = if ("icon" in def) def["icon"].string()!! else "",
        xp = if ("xp" in def) def["xp"].finiteNumber()!! else 0.0,
        check = check,
    )
}

/** A clock the case moves. It names days in [zone] from the local wall time, never through UTC. */
private class FixedClock(private val zone: TimeZone, wall: String) : Clock {
    private val system = SystemClock(zone)
    private var millis = at(wall)

    fun at(wall: String): Long = LocalDateTime.parse(wall).toInstant(zone).toEpochMilliseconds()
    fun set(wall: String) {
        millis = at(wall)
    }

    override fun now(): Long = millis
    override fun localDate(millis: Long): String = system.localDate(millis)
}

/** An in-memory store. [failWrites] makes every set throw. Seeding goes around it. */
private class FixtureStorage(private val failWrites: Boolean) : StoragePort {
    val values = HashMap<String, String>()
    override suspend fun get(key: String): String? = values[key]
    override suspend fun set(key: String, value: String) {
        if (failWrites) throw IllegalStateException("storage write failed")
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private val EVENT_NAMES = listOf(
    "xp_gained", "level_up", "achievement_unlocked", "personal_best",
    "streak_updated", "player_reset", "save_failed", "load_failed",
)

internal class FixtureCase(private val case: JsonObject, zone: TimeZone) {
    private val clock = FixedClock(zone, case["clock"].string()!!)
    private val registry = HashMap<String, JsonElement>()
    private val storage = FixtureStorage((case["storage"] as? JsonObject)?.get("failWrites").bool() == true)
    private val events = mutableListOf<JsonObject>()
    private val config: ProgressConfig
    private lateinit var progress: Progress

    init {
        val today = clock.localDate()
        check(today == case["clock"].string()!!.substring(0, 10)) { "clock day $today is not the wall day" }
        val given = case["config"] as? JsonObject
        val defs = LinkedHashMap<String, AchievementDefinition>()
        for ((id, def) in (given?.get("achievements") as? JsonObject) ?: JsonObject(emptyMap())) {
            defs[id] = definitionOf(id, def as JsonObject)
        }
        config = ProgressConfig(
            achievements = defs,
            builtinAchievements = given?.get("builtinAchievements").bool() != false,
            clock = clock,
            streakDays = if (given?.get("streakDays").string() == "record") StreakDays.Record else StreakDays.Any,
        )
        // Stored as JSON text, like JSON.stringify. savedJson wins over savedRaw.
        case["device"]?.let { storage.values["dopamine_$DEVICE_KEY"] = it.toString() }
        val savedJson = case["savedJson"]
        if (savedJson != null) {
            storage.values["dopamine_player"] = savedJson.toString()
        } else if ("savedRaw" in case) {
            storage.values["dopamine_player"] = case["savedRaw"].string() ?: fail("savedRaw must be a string")
        }
    }

    private suspend fun start() {
        progress = Progress(storage, config)
        // Subscribed before init, so load events are seen.
        for (name in EVENT_NAMES) {
            progress.on(name) { event ->
                events += JsonObject((event.payload() as JsonObject) + ("event" to JsonPrimitive(event.wireName)))
            }
        }
        progress.init()
    }

    private fun docOf(value: JsonElement?, where: String): JsonElement {
        val name = value.string()
        if (name != null) return registry[name] ?: fail("$where: no saved document named \"$name\"")
        return value ?: fail("$where: missing document")
    }

    suspend fun run() {
        start()
        val afterInit = case["afterInit"] as? JsonObject
        if (afterInit != null) verify(afterInit, "afterInit", null)
        val steps = case["steps"] as? JsonArray ?: JsonArray(emptyList())
        for ((index, element) in steps.withIndex()) {
            val step = element as JsonObject
            val op = step["op"].string()!!
            val label = "step ${index + 1} ($op)"
            events.clear()
            var returned: JsonElement? = null
            var thrown: Throwable? = null
            try {
                returned = perform(op, step, label)
            } catch (error: DopamineError) {
                thrown = error
            } catch (error: ArgumentTypeError) {
                thrown = error
            }
            val expect = step["expect"] as? JsonObject
            if (expect == null) {
                // Only setClock may leave expect out.
                if (thrown != null) throw thrown
                continue
            }
            val wantThrow = expect["throws"].string()
            if (wantThrow != null) {
                val error = thrown ?: fail("$label: expected $wantThrow, nothing was thrown")
                val name = if (error is DopamineError) "DopamineError" else "TypeError"
                assertEquals(wantThrow, name, "$label error")
                if ("code" in expect) assertEquals(expect["code"].string(), (error as? DopamineError)?.code, "$label error code")
            } else if (thrown != null) {
                throw thrown
            }
            verify(expect, label, returned)
        }
    }

    // Ops use the raw entries, so arguments the typed API cannot take still reach the checks.
    private suspend fun perform(op: String, step: JsonObject, label: String): JsonElement? {
        val kept: JsonElement? = when (op) {
            "setClock" -> {
                clock.set(step["at"].string()!!)
                null
            }
            "reload" -> {
                step["device"]?.let { storage.values["dopamine_$DEVICE_KEY"] = it.toString() }
                events.clear()
                start()
                null
            }
            "addXP" -> {
                val result = progress.addXpRaw(step["amount"], step["reason"])
                JsonObject(
                    mapOf(
                        "leveledUp" to JsonPrimitive(result.leveledUp),
                        "newLevel" to JsonPrimitive(result.newLevel),
                        "xpGained" to number(result.xpGained),
                    ),
                )
            }
            "record" -> JsonPrimitive(progress.recordRaw(step["activity"], step["result"]))
            "unlockAchievement" -> JsonPrimitive(progress.unlockAchievementRaw(step["id"]))
            "markAchievementsSeen" -> {
                progress.markAchievementsSeen((step["ids"] as JsonArray).map { it.string()!! })
                null
            }
            "reset" -> {
                val raw = step["overwrite"]
                if (raw == null) {
                    progress.reset()
                } else {
                    progress.reset(overwrite = raw.bool() ?: fail("reset overwrite must be a boolean"))
                }
            }
            "getXPForNextLevel" -> {
                val next = progress.getXpForNextLevel()
                JsonObject(
                    mapOf(
                        "total" to number(next.total),
                        "needed" to number(next.needed),
                        "progress" to number(next.progress),
                    ),
                )
            }
            "export" -> progress.export().also { registry[step["as"].string()!!] = it }
            "import" -> {
                val from = step["from"].string()
                val doc = if (from != null) registry[from] ?: fail("$label: no saved document named \"$from\"") else step["doc"] ?: fail("$label: missing document")
                progress.import(doc, silent = step["silent"].bool() == true)
            }
            "merge" -> merge(docOf(step["a"], label), docOf(step["b"], label)).also { doc -> step["as"].string()?.let { registry[it] = doc } }
            "validate" -> validate(docOf(step["doc"], label)).also { doc -> step["as"].string()?.let { registry[it] = doc } }
            else -> fail("$label: unknown op")
        }
        return kept
    }

    private fun verify(expectation: JsonObject, label: String, returned: JsonElement?) {
        assertKeys("$label expect", expectation, EXPECT_KEYS)
        nonEmpty(expectation, "$label expect")
        for (key in listOf("player", "playerExact", "doc", "docExact")) {
            val value = expectation[key] ?: continue
            if (value !is JsonObject) fail("$label $key: expected an object")
            nonEmpty(value, "$label $key")
        }
        (expectation["returns"] as? JsonObject)?.let { nonEmpty(it, "$label returns") }
        (expectation["returnsExact"] as? JsonObject)?.let { nonEmpty(it, "$label returnsExact") }
        for (key in listOf("absent", "achievements", "events")) {
            val value = expectation[key] ?: continue
            if (value !is JsonArray) fail("$label $key: expected an array")
            if (key == "absent") nonEmpty(value, "$label $key")
        }

        val player = progress.snapshot()
        val exported = progress.export()
        expectation["player"]?.let { matchSubset(player, it, "$label player") }
        exact(expectation["playerExact"], player, "$label playerExact")
        expectation["doc"]?.let { matchSubset(exported, it, "$label doc") }
        exact(expectation["docExact"], exported, "$label docExact")
        expectation["returnsExact"]?.let { want ->
            if (want is JsonObject) exact(want, returned, "$label returnsExact")
            else if (!deepEqual(returned, want, strict = true)) fail("$label returnsExact: expected $want, got $returned")
        }
        (expectation["same"])?.let { same ->
            val pairs = same as? JsonArray
            if (pairs == null || pairs.isEmpty()) fail("$label same: expected a list of pairs")
            for (pair in pairs) {
                val names = (pair as? JsonArray)?.map { it.string() }
                val a = names?.getOrNull(0)?.let { registry[it] }
                val b = names?.getOrNull(1)?.let { registry[it] }
                if (names == null || names.size != 2 || a == null || b == null) fail("$label same: $pair must name two saved documents")
                if (!deepEqual(a, b)) fail("$label same ${names[0]} and ${names[1]}: documents differ")
            }
        }
        for (path in (expectation["absent"] as? JsonArray) ?: emptyList<JsonElement>()) {
            if (getPath(player, path.string()!!) != null) fail("$label absent.${path.string()}: present")
        }
        (expectation["achievements"] as? JsonArray)?.let { want ->
            val got = (player["achievements"] as JsonObject).keys.sorted()
            assertEquals(want.map { it.string()!! }.sorted(), got, "$label achievements")
        }
        expectation["returns"]?.let { matchSubset(returned, it, "$label returns") }
        (expectation["events"] as? JsonArray)?.let { want ->
            val got = events.toList()
            assertEquals(want.size, got.size, "$label events: expected ${want.size}, got $got")
            want.forEachIndexed { i, event -> matchSubset(got[i], event, "$label events[$i]") }
        }
    }

    private fun exact(want: JsonElement?, root: JsonElement?, label: String) {
        val paths = want as? JsonObject ?: return
        for ((path, expected) in paths) {
            val found = getPath(root, path) ?: fail("$label.$path: missing")
            if (!deepEqual(found, expected, strict = true)) fail("$label.$path: expected $expected, got $found")
        }
    }

    companion object {
        /** Checks the shape of a case and fails on anything unknown. */
        fun inspect(case: JsonObject) {
            assertKeys("case", case, CASE_KEYS)
            if (!CLOCK_SHAPE.matches(case["clock"].string() ?: "")) fail("clock must be YYYY-MM-DDTHH:MM:SS local time")
            if ("steps" in case && case["steps"] !is JsonArray) fail("steps must be an array")
            if ("afterInit" in case && case["afterInit"] !is JsonObject) fail("afterInit must be an object")
            if ("savedRaw" in case && case["savedRaw"].string() == null) fail("savedRaw must be a string")
            val steps = case["steps"] as? JsonArray
            if (case["afterInit"] == null && (steps == null || steps.isEmpty())) fail("case has no afterInit and no steps, so it checks nothing")
            (case["storage"] as? JsonObject)?.let { assertKeys("storage", it, STORAGE_OPTION_KEYS) }

            val config = case["config"] as? JsonObject
            if (config != null) {
                assertKeys("config", config, CONFIG_KEYS)
                config["streakDays"]?.let {
                    if (it.string() !in setOf("any", "record")) fail("config.streakDays must be \"any\" or \"record\"")
                }
                for ((id, def) in (config["achievements"] as? JsonObject) ?: JsonObject(emptyMap())) {
                    val obj = def as JsonObject
                    assertKeys("achievement $id", obj, ACHIEVEMENT_KEYS)
                    for (key in listOf("name", "description", "icon")) {
                        if (key in obj && obj[key].string() == null) fail("achievement $id: \"$key\" must be a string")
                    }
                    if ("xp" in obj && obj["xp"].finiteNumber() == null) fail("achievement $id: \"xp\" must be a number")
                    checkCondition(id, obj["when"])
                }
            }

            for ((index, element) in (steps ?: JsonArray(emptyList())).withIndex()) {
                val step = element as? JsonObject ?: fail("step ${index + 1}: expected an object")
                val op = step["op"].string()
                val label = "step ${index + 1} ($op)"
                val allowed = OPS[op] ?: fail("$label: unknown op")
                assertKeys(label, step, allowed + setOf("op", "expect"))
                if (op == "merge") listOf("a", "b").forEach { if (it !in step) fail("$label: $it is required") }
                if (op == "validate" && "doc" !in step) fail("$label: doc is required")
                if (op == "import" && "doc" !in step && "from" !in step) fail("$label: doc or from is required")
                if ("expect" in step && step["expect"] !is JsonObject) fail("$label: expect must be an object")
                if (op != "setClock" && "expect" !in step) fail("$label: expect is required")
                if (op == "setClock" && !CLOCK_SHAPE.matches(step["at"].string() ?: "")) fail("$label: at must be YYYY-MM-DDTHH:MM:SS local time")
            }
        }
    }
}
