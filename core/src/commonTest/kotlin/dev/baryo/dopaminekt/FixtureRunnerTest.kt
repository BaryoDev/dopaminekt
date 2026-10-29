package dev.baryo.dopaminekt

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerializationException
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
 * Runs the language-neutral cases in spec/core/fixtures for the document layer:
 * the validate and merge steps and the saved players that only need validate.
 * Cases that need the reward system are skipped by [K3B_OPS] and the reasons
 * in [skipReason]. Anything the runner does not understand fails the test.
 */
class FixtureRunnerTest {
    private val zones = listOf("Asia/Manila", "America/Los_Angeles")

    // File to (run, skipped). Skipped cases need the reward system. A fixture change must update these.
    private val EXPECTED_COUNTS = mapOf(
        "achievements.json" to (0 to 7),
        "levels.json" to (0 to 6),
        "merge.json" to (11 to 16),
        "migrate.json" to (33 to 1),
        "scores.json" to (0 to 13),
        "streaks.json" to (4 to 13),
    )

    @Test
    fun fixtureRunnerRunsEveryDocumentCase() {
        val failures = mutableListOf<String>()
        val counts = mutableMapOf<String, Pair<Int, Int>>()
        assertTrue(FixtureData.files.isNotEmpty(), "no fixture files were generated")

        for ((file, text) in FixtureData.files.entries.sortedBy { it.key }.map { it.key to it.value }) {
            val suite = Json.parseToJsonElement(text) as JsonObject
            val cases = suite["cases"] as? JsonArray
            assertTrue(cases != null && cases.isNotEmpty(), "$file has no cases")
            var run = 0
            var skipped = 0
            for (element in cases) {
                val case = element as JsonObject
                val name = (case["name"] as JsonPrimitive).content
                try {
                    val reason = FixtureCase.inspect(case)
                    if (reason != null) {
                        skipped++
                        continue
                    }
                    run++
                    for (zone in zones) {
                        try {
                            FixtureCase(case, TimeZone.of(zone)).run()
                        } catch (error: Throwable) {
                            failures += "$file / $name / $zone: ${error.message}"
                        }
                    }
                } catch (error: Throwable) {
                    failures += "$file / $name: ${error.message}"
                }
            }
            counts[file] = run to skipped
            println("fixture runner $file: $run run, $skipped skipped (K3b)")
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(EXPECTED_COUNTS, counts, "cases run and skipped per file")
    }
}

/** Ops that need the reward system. A case using one is skipped with the reason K3b. */
private val K3B_OPS = setOf(
    "setClock", "reload", "addXP", "record", "unlockAchievement", "markAchievementsSeen",
    "reset", "getXPForNextLevel", "export", "import",
)

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
    "reset" to emptySet(),
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

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun getPath(root: JsonElement?, path: String): JsonElement? {
    var node = root
    for (part in path.split(".")) {
        node = (node as? JsonObject)?.get(part) ?: return null
    }
    return node
}

private fun primitiveEquals(a: JsonPrimitive, b: JsonPrimitive): Boolean {
    if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
    if (a.isString || b.isString) return a.isString && b.isString && a.content == b.content
    val x = a.finiteNumber()
    val y = b.finiteNumber()
    return if (x != null && y != null) x == y else a.content == b.content
}

private fun deepEqual(a: JsonElement?, b: JsonElement?): Boolean = when {
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { deepEqual(a[it], b[it]) }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { deepEqual(a[it], b[it]) }
    a is JsonPrimitive && b is JsonPrimitive -> primitiveEquals(a, b)
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

/** The XP a 2.x save holds for each achievement id: the built-ins, then the case's own. */
private fun rewardsOf(config: JsonObject?): Map<String, Double> {
    val rewards = LinkedHashMap(BUILTIN_REWARDS)
    val defs = config?.get("achievements") as? JsonObject ?: return rewards
    for ((id, def) in defs) {
        val xp = (def as JsonObject)["xp"].finiteNumber()
        rewards[id] = if (xp != null && xp > 0) xp else 0.0
    }
    return rewards
}

private class FixedClock(private val zone: TimeZone, private val millis: Long) : Clock {
    private val system = SystemClock(zone)
    override fun now(): Long = millis
    override fun localDate(millis: Long): String = system.localDate(millis)
}

private class FixtureCase(private val case: JsonObject, zone: TimeZone) {
    private val clock: Clock
    private val registry = HashMap<String, JsonElement>()
    private val loaded: Doc
    private val today: String
    private var loadFailure: String? = null

    init {
        val wall = LocalDateTime.parse(case["clock"].string()!!)
        clock = FixedClock(zone, wall.toInstant(zone).toEpochMilliseconds())
        today = clock.localDate()
        check(today == case["clock"].string()!!.substring(0, 10)) { "clock day $today is not the wall day" }
        val config = case["config"] as? JsonObject
        val rewards = rewardsOf(config)
        // Same as init: nothing saved, or a value that is not an object, starts an empty player.
        // Text that does not parse, or a document the core refuses, does too, and load_failed is raised.
        val raw = case["savedRaw"].string()
        val saved: JsonElement? = when {
            raw != null -> try {
                parseJsonStrict(raw)
            } catch (error: SerializationException) {
                loadFailure = "corrupt"
                null
            }
            else -> case["savedJson"]
        }
        loaded = if (saved is JsonObject) {
            try {
                parseDoc(saved, clock.now(), today, rewards)
            } catch (error: DopamineError) {
                loadFailure = error.code
                emptyDoc(clock.now().toDouble())
            }
        } else {
            emptyDoc(clock.now().toDouble())
        }
    }

    private fun docOf(value: JsonElement?, where: String): JsonElement {
        val name = value.string()
        if (name != null) return registry[name] ?: fail("$where: no saved document named \"$name\"")
        return value ?: fail("$where: missing document")
    }

    fun run() {
        val afterInit = case["afterInit"] as? JsonObject
        if (afterInit != null) verify(afterInit, "afterInit", null, listOfNotNull(loadFailure))
        val steps = case["steps"] as? JsonArray ?: JsonArray(emptyList())
        for ((index, element) in steps.withIndex()) {
            val step = element as JsonObject
            val op = step["op"].string()!!
            val label = "step ${index + 1} ($op)"
            var returned: JsonElement? = null
            var thrown: Throwable? = null
            try {
                when (op) {
                    "merge" -> {
                        returned = merge(docOf(step["a"], label), docOf(step["b"], label))
                        step["as"].string()?.let { registry[it] = returned }
                    }
                    "validate" -> {
                        returned = validate(docOf(step["doc"], label))
                        step["as"].string()?.let { registry[it] = returned }
                    }
                    else -> fail("$label: op is not a document op")
                }
            } catch (error: DopamineError) {
                thrown = error
            } catch (error: ArgumentTypeError) {
                thrown = error
            }
            val expect = step["expect"] as JsonObject
            val wantThrow = (expect["throws"] as? JsonPrimitive)?.content
            if (wantThrow != null) {
                val error = thrown ?: fail("$label: expected $wantThrow, nothing was thrown")
                val name = if (error is DopamineError) "DopamineError" else "TypeError"
                assertEquals(wantThrow, name, "$label error")
                if ("code" in expect) assertEquals(expect["code"].string(), (error as? DopamineError)?.code, "$label error code")
            } else if (thrown != null) {
                throw thrown
            }
            verify(expect, label, returned, emptyList())
        }
    }

    private fun verify(expectation: JsonObject, label: String, returned: JsonElement?, loadFailures: List<String>) {
        assertKeys("$label expect", expectation, EXPECT_KEYS)
        nonEmpty(expectation, "$label expect")
        for (key in listOf("player", "playerExact", "doc", "docExact", "returnsExact")) {
            val value = expectation[key] ?: continue
            if (value !is JsonObject) fail("$label $key: expected an object")
            nonEmpty(value, "$label $key")
        }
        (expectation["returns"] as? JsonObject)?.let { nonEmpty(it, "$label returns") }
        for (key in listOf("absent", "achievements", "events")) {
            val value = expectation[key] ?: continue
            if (value !is JsonArray) fail("$label $key: expected an array")
            if (key == "absent") nonEmpty(value, "$label $key")
        }

        val player = view(loaded, today)
        val exported = render(loaded)
        expectation["player"]?.let { matchSubset(player, it, "$label player") }
        exact(expectation["playerExact"], player, "$label playerExact")
        expectation["doc"]?.let { matchSubset(exported, it, "$label doc") }
        exact(expectation["docExact"], exported, "$label docExact")
        exact(expectation["returnsExact"], returned, "$label returnsExact")
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
        (expectation["events"] as? JsonArray)?.let { events ->
            // The only event this runner can see is load_failed, with the reason the load failed for.
            val got = loadFailures.map { "load_failed:$it" }
            val want = events.map { "${(it as JsonObject)["event"].string()}:${it["reason"].string()}" }
            assertEquals(want, got, "$label events")
        }
    }

    private fun exact(want: JsonElement?, root: JsonElement?, label: String) {
        val paths = want as? JsonObject ?: return
        for ((path, expected) in paths) {
            val found = getPath(root, path) ?: fail("$label.$path: missing")
            if (!deepEqual(found, expected)) fail("$label.$path: expected $expected, got $found")
        }
    }

    companion object {
        /**
         * Checks the shape of a case, failing on anything unknown, and returns
         * why it is skipped, or null when this runner can run it.
         */
        fun inspect(case: JsonObject): String? {
            assertKeys("case", case, CASE_KEYS)
            if (!CLOCK_SHAPE.matches(case["clock"].string() ?: "")) fail("clock must be YYYY-MM-DDTHH:MM:SS local time")
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
                    checkCondition(id, obj["when"])
                }
            }

            var usesReward = false
            for ((index, element) in (steps ?: JsonArray(emptyList())).withIndex()) {
                val step = element as JsonObject
                val op = step["op"].string()
                val label = "step ${index + 1} ($op)"
                val allowed = OPS[op] ?: fail("$label: unknown op")
                assertKeys(label, step, allowed + setOf("op", "expect"))
                if (op == "merge") listOf("a", "b").forEach { if (it !in step) fail("$label: $it is required") }
                if (op == "validate" && "doc" !in step) fail("$label: doc is required")
                if (op != "setClock" && step["expect"] == null) fail("$label: expect is required")
                if (op == "setClock" && !CLOCK_SHAPE.matches(step["at"].string() ?: "")) fail("$label: at must be YYYY-MM-DDTHH:MM:SS local time")
                if (op in K3B_OPS) usesReward = true
            }

            // load_failed is raised by loading a save, which the runner can do. Other events need the reward system.
            val otherEvents = ((case["afterInit"] as? JsonObject)?.get("events") as? JsonArray)
                ?.any { (it as JsonObject)["event"].string() != "load_failed" } == true
            return skipReason(usesReward = usesReward, failingWrites = "storage" in case, otherEvents = otherEvents)
        }

        private fun skipReason(usesReward: Boolean, failingWrites: Boolean, otherEvents: Boolean): String? = when {
            usesReward -> "K3b"
            failingWrites -> "K3b"
            otherEvents -> "K3b"
            else -> null
        }
    }
}
