package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What marks today as a play day. */
public enum class StreakDays {
    /** A record or an addXp call. */
    Any,

    /** A record call only. XP from an achievement reward never marks a day. */
    Record,
}

/**
 * Options for [Progress].
 *
 * @property achievements extra achievement definitions, added after the built in ones.
 * @property builtinAchievements false defines none of the five built in achievements. A save that
 * already holds one keeps it and its XP.
 * @property clock the time source.
 * @property streakDays what marks today as a play day.
 * @property prefix the storage key prefix. An empty one means `dopamine_`.
 * @property onError receives errors that are caught and skipped: a failed storage write, a
 * throwing listener, hook or achievement check. It does nothing by default.
 */
public class ProgressConfig(
    public val achievements: Map<String, AchievementDefinition> = emptyMap(),
    public val builtinAchievements: Boolean = true,
    public val clock: Clock = SystemClock(),
    public val streakDays: StreakDays = StreakDays.Any,
    public val prefix: String = DEFAULT_PREFIX,
    public val onError: (Throwable) -> Unit = {},
)

/** The result of adding XP. [xpGained] is negative when XP was spent, and stops at the total. */
public data class AddXpResult(val leveledUp: Boolean, val newLevel: Long, val xpGained: Double)

/** XP needed for the next level: its [total] threshold, what is [needed] still, and [progress] from 0 to 1. */
public data class XpProgress(val total: Double, val needed: Double, val progress: Double)

private const val PLAYER_KEY = "player"

/**
 * One player's progress: XP, levels, streaks, play stats and achievements, saved to a
 * [StoragePort] as a version 3 document that merges with other devices.
 *
 * Call [init] and wait for it before any other call. Until then the methods throw
 * [IllegalStateException].
 *
 * Use one coroutine dispatcher for all calls. The player is not locked, so calls from two
 * dispatchers at once can interleave. The caller keeps them on one, for example the main thread
 * or a single thread dispatcher.
 *
 * A bad argument throws [IllegalArgumentException]. Nothing is changed when it does.
 *
 * Nothing hands the document out: [snapshot], [export] and the achievement lists are immutable
 * JSON, so a caller cannot change the player through them.
 */
public class Progress(storage: StoragePort, config: ProgressConfig = ProgressConfig()) {
    private val clock: Clock = config.clock
    private val streakDays: StreakDays = config.streakDays
    private val onError: (Throwable) -> Unit = config.onError
    private val data = DataService(storage, config.prefix, onError)
    private val emitter = EventEmitter(onError)
    private val chain = MiddlewareChain(onError)
    private val achievements: MutableMap<String, AchievementDefinition> = LinkedHashMap<String, AchievementDefinition>().apply {
        if (config.builtinAchievements) putAll(builtinAchievements())
        putAll(config.achievements)
    }

    private var doc: Doc? = null
    private var deviceId: String? = null
    private var deviceSaved = false

    // Write batching: one record is one write.
    private var batchDepth = 0
    private var pendingSave = false

    // Set when the saved player could not be used. Saving stays off until a later init reads it
    // or reset is called, so the saved player is never replaced by accident.
    private var blocked = false
    private var readFailed = false

    /**
     * True when the last [init] could not read the saved player, so nothing is saved until [init]
     * is called again and the read works, or [reset] is called.
     */
    public val loadFailed: Boolean get() = readFailed

    // Events

    /**
     * Calls [listener] for each event with [wireName] (see [ProgressEvent.wireName]). Returns a
     * function that removes it. Emit is synchronous. A listener added during an emit is not
     * called for that emit. Adding the same listener twice keeps one.
     */
    public fun on(wireName: String, listener: (ProgressEvent) -> Unit): () -> Unit = emitter.on(wireName, listener)

    /**
     * Delivers a plugin [event] to the listeners of its name, in order, synchronously. A throwing
     * listener is passed to the error callback and skipped, as for any event.
     *
     * Throws [DopamineError] with the code `plugin_event` when the name has no `:` or starts with
     * `:`, so a plugin can never send a core event such as `xp_gained`. A name like `a:` is
     * accepted. The check does not prove the prefix is the caller's own plugin name. That is only
     * guaranteed for a plugin that emits through `PluginContext.events`.
     */
    public fun emit(event: ProgressEvent.Custom) {
        val name = event.wireName
        if (name.indexOf(':') <= 0) {
            throw DopamineError("plugin_event", "Plugin event names look like <plugin>:<event>, got \"$name\"")
        }
        publish(event)
    }

    /** Removes every listener. */
    public fun clear() {
        emitter.clear()
    }

    private fun publish(event: ProgressEvent) {
        emitter.emit(event)
    }

    // Middleware and achievements

    /** Adds [middleware]. Hooks run in the order added. Returns a function that removes it. */
    public fun use(middleware: Middleware): () -> Unit = chain.use(middleware)

    /**
     * Adds an achievement rule. [definition] needs a check. Returns a function that removes it
     * again, which puts back the definition it replaced.
     *
     * @throws IllegalArgumentException when [id] is not a safe key or [definition] has no check.
     */
    public fun defineAchievement(id: String, definition: AchievementDefinition): () -> Unit {
        if (!isSafeKey(id)) throw ArgumentTypeError("defineAchievement expects an id, received $id")
        if (definition.check == null) throw ArgumentTypeError("defineAchievement expects a definition with a check")
        val previous = achievements[id]
        achievements[id] = definition
        return {
            if (achievements[id] === definition) {
                if (previous != null) achievements[id] = previous else achievements.remove(id)
            }
        }
    }

    // Loading

    private fun requirePlayer(): Doc = doc ?: throw IllegalStateException("Progress.init() must be awaited before use")

    private fun options(): Triple<Long, String, Map<String, Double>> =
        Triple(clock.now(), clock.localDate(), BUILTIN_REWARDS + rewardsOf(achievements))

    /**
     * Reads the saved player, or starts a new one, and returns a snapshot. Calling it again after
     * a failed read retries the read and keeps the progress made since.
     */
    public suspend fun init(): JsonObject {
        val wasBlocked = blocked
        val wasReadFailed = readFailed
        try {
            val saved = readSaved()
            // A failed retry keeps the progress made since the first init.
            val loaded = if (blocked && doc != null) doc!! else loadDoc(saved)
            val device = deviceId?.let { DeviceId(it, deviceSaved) } ?: data.readDevice()
            // Nothing is set until every read is done, so a cancelled init leaves Progress as it was.
            doc = loaded
            deviceId = device.id
            deviceSaved = device.saved
        } catch (e: CancellationException) {
            blocked = wasBlocked
            readFailed = wasReadFailed
            throw e
        }
        save()
        return snapshot()
    }

    // A failed read turns saving off for this session, so the new player built from the default
    // never overwrites the real one.
    private suspend fun readSaved(): JsonElement? {
        blocked = true
        try {
            val saved = data.load(PLAYER_KEY, strict = true)
            blocked = false
            readFailed = false
            return saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: CorruptDataException) {
            // Keep the unreadable value instead of losing it on the next save.
            val kept = data.save("player_corrupt", JsonPrimitive(e.raw))
            if (kept) blocked = false
            readFailed = !kept
            publish(ProgressEvent.LoadFailed(PLAYER_KEY, "corrupt", backedUp = kept))
            return null
        } catch (e: Exception) {
            readFailed = true
            publish(ProgressEvent.LoadFailed(PLAYER_KEY, "read", error = e))
            return null
        }
    }

    // Saved state is untrusted. A document from a newer version or with too many devices is left
    // alone in storage.
    private fun loadDoc(saved: JsonElement?): Doc {
        if (saved !is JsonObject) return emptyDoc(clock.now().toDouble())
        val (now, today, rewards) = options()
        return try {
            parseDoc(saved, now, today, rewards)
        } catch (e: DopamineError) {
            blocked = true
            readFailed = true
            publish(ProgressEvent.LoadFailed(PLAYER_KEY, e.code, error = e))
            emptyDoc(clock.now().toDouble())
        }
    }

    // A write that would add a device past the cap is refused, so the stored save never becomes
    // one that validate rejects.
    private fun hasSlot(): Boolean {
        val ids = deviceIds(requirePlayer())
        if (deviceId in ids || ids.size < MAX_DEVICES) return true
        publish(ProgressEvent.LoadFailed(PLAYER_KEY, "too_many_devices"))
        return false
    }

    // Views

    /** The player with derived values: xp, level, totalGamesPlayed, streak and per activity stats. */
    public fun snapshot(): JsonObject = view(requirePlayer(), clock.localDate())

    /** The versioned document. */
    public fun export(): JsonObject = render(requirePlayer())

    /** XP needed for the next level. */
    public fun getXpForNextLevel(): XpProgress {
        val player = requirePlayer()
        val level = levelOf(player)
        val xp = xpOf(player)
        val needed = thresholdFor(level + 1)
        val current = thresholdFor(level)
        val band = needed - current
        // Clamped: a level banked under an older curve can sit above its own XP floor. Those
        // players keep the level and start the band at 0.
        val progress = if (band > 0) max(0.0, min(1.0, (xp - current) / band)) else 0.0
        return XpProgress(needed, max(0.0, needed - xp), progress)
    }

    /** The XP multiplier for the current streak: 2 from 7 days, 1.5 from 5, 1.25 from 3, else 1. */
    public fun getStreakMultiplier(): Double {
        val streak = currentStreak(requirePlayer().playDates, clock.localDate())
        return when {
            streak >= 7 -> 2.0
            streak >= 5 -> 1.5
            streak >= 3 -> 1.25
            else -> 1.0
        }
    }

    // Saving

    /** Saves the player. Inside a record the write waits for the end of the outermost one. */
    public suspend fun save() {
        if (doc == null) return
        // Inside a batch, mark dirty and let the outermost call do the write.
        if (batchDepth > 0) {
            pendingSave = true
            return
        }
        write()
    }

    // The actual persistence call. Bypasses batching. It runs to the end even when the caller is
    // cancelled, so memory is never ahead of storage.
    private suspend fun write() {
        if (blocked) return
        withContext(NonCancellable) {
            val player = requirePlayer()
            player.lastPlayedAt = clock.now().toDouble()
            // The device id is written with every write until it works, and a failure is not announced.
            if (!deviceSaved) deviceSaved = data.saveDevice(deviceId!!)
            if (!data.save(PLAYER_KEY, render(player))) publish(ProgressEvent.SaveFailed(PLAYER_KEY))
        }
    }

    private suspend fun <T> batch(block: suspend () -> T): T {
        batchDepth++
        try {
            return block()
        } finally {
            batchDepth--
            if (batchDepth == 0 && pendingSave) {
                pendingSave = false
                try {
                    write()
                } catch (e: Throwable) {
                    pendingSave = true
                    throw e
                }
            }
        }
    }

    // Import and reset

    /**
     * Merges a document from elsewhere into this player. It is checked the way a load is. Unless
     * [silent], the XP, level and achievements that went up are announced. Returns a snapshot.
     *
     * @throws DopamineError when [document] or the result is not usable.
     */
    public suspend fun import(document: JsonElement, silent: Boolean = false): JsonObject {
        val before = requirePlayer()
        val beforeXp = xpOf(before)
        val beforeLevel = levelOf(before)
        val beforeIds = before.achievements.keys.toSet()
        val (now, today, rewards) = options()
        parseDoc(document, now, today, rewards)
        val merged = parseDoc(merge(render(before), document, now, today, rewards), now, today, rewards)
        merged.level = levelOf(merged)
        doc = merged

        val after = snapshot()
        save()
        if (silent) return after

        val total = xpOf(merged)
        val level = levelOf(merged)
        val gained = total - beforeXp
        val leveledUp = level > beforeLevel
        if (gained > 0) publish(ProgressEvent.XpGained(gained, "import", leveledUp, level, total))
        if (leveledUp) publish(ProgressEvent.LevelUp(beforeLevel, level, total))
        for (id in merged.achievements.keys.toList()) {
            // An id with no definition stays in the save but has nothing to announce.
            val definition = achievements[id]
            if (id !in beforeIds && definition != null) publish(unlocked(id, definition))
        }
        return after
    }

    /**
     * Replaces the player with a new one and saves it. The device id stays, so the new player
     * counts under the same device. After the save it publishes `player_reset`, so wrappers can
     * refresh.
     *
     * This is an explicit request, so it also writes when the last [init] could not read the
     * saved player: the stored player is replaced, without a backup, and [loadFailed] goes back
     * to false.
     *
     * Pass `overwrite = false` to keep the stored player when the last [init] could not read it
     * (an unsupported newer version, or a read that keeps failing). The player in memory is
     * replaced by a new empty one, nothing is written, [loadFailed] stays true so later writes
     * stay blocked, and `player_reset` is still published. When the last [init] did read the
     * saved player, `overwrite = false` changes nothing: it saves like a plain `reset()`. A
     * corrupt save that [init] backed up to `player_corrupt` counts as loaded, so
     * `overwrite = false` saves like a plain `reset()`. If the backup failed, it counts as a
     * failed load and nothing is written.
     *
     * Timing: call it after [init] has finished, not while it is pending. A record already in
     * flight when reset runs lands in the new player. Called from inside a hook, the write
     * happens when the outer record or unlock finishes.
     *
     * @param overwrite false keeps the stored player after a failed load.
     * @return a snapshot of the new player.
     */
    public suspend fun reset(overwrite: Boolean = true): JsonObject {
        requirePlayer()
        if (!overwrite && readFailed) {
            doc = emptyDoc(clock.now().toDouble())
            publish(ProgressEvent.PlayerReset)
            return snapshot()
        }
        batch {
            doc = emptyDoc(clock.now().toDouble())
            blocked = false
            readFailed = false
            save()
        }
        publish(ProgressEvent.PlayerReset)
        return snapshot()
    }

    // XP

    /**
     * Adds XP. A negative amount is kept as XP spent on this device and stops at zero.
     *
     * @throws IllegalArgumentException when [amount] is not finite.
     * @throws IllegalStateException before [init].
     */
    public suspend fun addXp(amount: Double, reason: String = ""): AddXpResult =
        addXpRaw(JsonPrimitive(amount), JsonPrimitive(reason))

    // The checks run in the JS order: the amount first, then the player. A missing reason is "".
    internal suspend fun addXpRaw(amount: JsonElement?, reason: JsonElement? = null): AddXpResult {
        // Adding NaN would poison the total for good, as NaN survives every later sum.
        val value = amount.finiteNumber() ?: throw ArgumentTypeError("addXp expects a finite number, received ${describe(amount)}")
        requirePlayer()
        return addXpChecked(value, reasonText(reason))
    }

    private suspend fun addXpChecked(amount: Double, reason: String): AddXpResult {
        if (!hasSlot()) return AddXpResult(false, levelOf(requirePlayer()), 0.0)

        val context = chain.before(BeforeXpContext(amount, reason), ::acceptXp) { m, c -> m.beforeXp(c) }
            ?: return AddXpResult(false, levelOf(requirePlayer()), 0.0)

        if (streakDays == StreakDays.Any && context.amount > 0) markDay()

        val result = applyXp(context.amount, context.reason)
        chain.after(AfterXpContext(result.xpGained, context.reason, xpOf(requirePlayer()), result.newLevel)) { m, c -> m.afterXp(c) }
        return result
    }

    // Levels are never taken back, so a negative amount or a level banked under an older curve
    // reports the stored one.
    private suspend fun applyXp(requested: Double, reason: String): AddXpResult {
        val player = requirePlayer()
        val oldLevel = levelOf(player)
        // XP stops at zero, so a large negative amount removes less than asked.
        val amount = max(requested, -xpOf(player))
        val device = deviceId!!
        if (amount >= 0) {
            player.xpBy[device] = capDouble((player.xpBy[device] ?: 0.0) + amount)
        } else {
            player.xpSpentBy[device] = capDouble((player.xpSpentBy[device] ?: 0.0) - amount)
        }
        return announceXp(oldLevel, amount, reason)
    }

    private suspend fun announceXp(oldLevel: Long, amount: Double, reason: String): AddXpResult {
        val player = requirePlayer()
        val total = xpOf(player)
        val newLevel = max(oldLevel, levelFor(total))
        val leveledUp = newLevel > oldLevel
        if (newLevel > player.level) player.level = newLevel

        save()

        publish(ProgressEvent.XpGained(amount, reason, leveledUp, newLevel, total))
        if (leveledUp) publish(ProgressEvent.LevelUp(oldLevel, newLevel, total))
        return AddXpResult(leveledUp, newLevel, amount)
    }

    // Records

    // Count today as a play day. A new player starts at 1 without an event.
    private fun markDay() {
        val player = requirePlayer()
        val today = clock.localDate()
        if (today in player.playDates) return
        val firstEver = player.playDates.isEmpty()
        player.playDates = trimDays(player.playDates + today, today)
        player.longest = max(player.longest, longestRun(player.playDates).toLong())
        if (!firstEver) {
            publish(
                ProgressEvent.StreakUpdated(
                    currentStreak(player.playDates, today).toLong(),
                    max(player.longest, longestRun(player.playDates).toLong()),
                ),
            )
        }
    }

    /**
     * Records a play and awards XP. In [result], `score` sets the best for [activity] and every
     * other number adds up per device. Returns false when a beforeRecord hook rejected it.
     *
     * @throws IllegalArgumentException when [activity] is empty or unsafe, or a value of [result]
     * named `score` is not finite.
     * @throws IllegalStateException before [init].
     */
    public suspend fun record(activity: String, result: Map<String, Double> = emptyMap()): Boolean =
        recordRaw(JsonPrimitive(activity), JsonObject(result.mapValues { JsonPrimitive(it.value) }))

    // The player is checked first, then the arguments. A missing result is {}, a null one is bad.
    internal suspend fun recordRaw(activity: JsonElement?, result: JsonElement? = null): Boolean {
        requirePlayer()
        val (name, fields) = checkRecord(activity, result ?: JsonObject(emptyMap()))
        if (!hasSlot()) return false

        val context = chain.before(BeforeRecordContext(name, fields), ::acceptRecord) { m, c -> m.beforeRecord(c) }
            ?: return false

        // Batched: addXp and each unlock below also save.
        val xp = batch { recordPlay(context.activity, context.result) }
        chain.after(AfterRecordContext(context.activity, context.result, xp)) { m, c -> m.afterRecord(c) }
        return true
    }

    // Checked before anything is touched, so a bad score cannot leave the play counted and the XP missing.
    private fun checkRecord(activity: JsonElement?, result: JsonElement): Pair<String, JsonObject> {
        val name = (activity as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (name == null || !isSafeKey(name)) throw ArgumentTypeError("record expects an activity name, received ${describe(activity)}")
        if (result !is JsonObject) throw ArgumentTypeError("record expects a result object")
        val score = result["score"]
        if (score != null && score.finiteNumber() == null) {
            throw ArgumentTypeError("record expects a finite score, received ${describe(score)}")
        }
        return name to result
    }

    private suspend fun recordPlay(activity: String, result: JsonObject): Double {
        val player = requirePlayer()
        val device = deviceId!!

        markDay()

        if (activity !in player.playsBy) {
            player.playsBy[activity] = LinkedHashMap()
            player.best[activity] = 0.0
            player.sumsBy[activity] = LinkedHashMap()
        }

        val plays = player.playsBy.getValue(activity)
        plays[device] = min(MAX_SAFE, (plays[device] ?: 0L) + 1)
        player.gamesBy[device] = min(MAX_SAFE, (player.gamesBy[device] ?: 0L) + 1)

        val score = result["score"].finiteNumber()
        if (score != null && score > (player.best[activity] ?: 0.0)) {
            player.best[activity] = score
            publish(ProgressEvent.PersonalBest(activity, score))
        }

        // Other numbers add up per device. A negative or non numeric value adds nothing, so a
        // device's total only grows.
        val sums = player.sumsBy.getValue(activity)
        for ((key, value) in result) {
            if (key == "score" || key == "plays" || key == "best" || !isSafeKey(key)) continue
            val by = sums.getOrPut(key) { LinkedHashMap() }
            val add = value.finiteNumber()?.takeIf { it > 0 } ?: 0.0
            by[device] = capDouble((by[device] ?: 0.0) + add)
        }

        // Base XP for playing, with a bonus for the score. A negative score earns nothing extra.
        var xp = 10.0
        if (score != null && score != 0.0) xp += max(0.0, floor(score / 5))
        xp = floor(xp * getStreakMultiplier())

        addXpChecked(xp, "Played $activity")
        checkAchievementsFor(activity, result)
        save()
        return xp
    }

    // Achievements

    /**
     * Unlocks every defined achievement whose check passes and which is not unlocked yet. Returns
     * the ones that unlocked. A check that throws is passed to the config's onError and skipped.
     */
    public suspend fun checkAchievements(activity: String? = null, result: JsonObject? = null): List<AchievementDefinition> {
        requirePlayer()
        return checkAchievementsFor(activity, result)
    }

    private suspend fun checkAchievementsFor(activity: String?, result: JsonObject?): List<AchievementDefinition> {
        val unlockedNow = ArrayList<AchievementDefinition>()
        for ((id, definition) in achievements.entries.map { it.key to it.value }) {
            if (id in requirePlayer().achievements) continue

            // Checks are supplied by the app. One throwing must not stop the others unlocking.
            val met = try {
                definition.check?.invoke(snapshot(), activity, result) ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                continue
            }

            if (met && unlockChecked(id)) unlockedNow.add(definition)
        }
        return unlockedNow
    }

    /**
     * Unlocks an achievement and pays its reward. Returns false when [id] is not defined, is
     * already unlocked, or a beforeUnlock hook rejected it.
     */
    public suspend fun unlockAchievement(id: String): Boolean = unlockAchievementRaw(JsonPrimitive(id))

    internal suspend fun unlockAchievementRaw(id: JsonElement?): Boolean {
        requirePlayer()
        val key = (id as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (key == null || !isSafeKey(key) || key !in achievements) return false
        return unlockChecked(key)
    }

    private suspend fun unlockChecked(id: String): Boolean {
        val player = requirePlayer()
        // Already unlocked: paying the XP again would let one call farm levels.
        if (id in player.achievements) return false
        val definition = achievements[id] ?: return false

        val reward = rewardsOf(achievements).getValue(id)
        val context = chain.before(BeforeUnlockContext(id, reward), ::acceptUnlock) { m, c -> m.beforeUnlock(c) }
            ?: return false

        val current = requirePlayer()
        val oldLevel = levelOf(current)
        current.achievements[id] = Achievement(clock.now().toDouble(), false, context.reward)

        // The reward is part of the achievement, not of any device's XP.
        announceXp(oldLevel, context.reward, "Achievement: ${definition.name}")

        publish(unlocked(id, definition))
        save()
        chain.after(AfterUnlockContext(id, context.reward)) { m, c -> m.afterUnlock(c) }
        return true
    }

    private fun unlocked(id: String, definition: AchievementDefinition): ProgressEvent.AchievementUnlocked =
        ProgressEvent.AchievementUnlocked(id, definition.name, definition.description, definition.icon, definition.xp)

    /** The unlocked achievements: the definition fields, then the id, then `unlockedAt`, `seen` and `reward`. */
    public fun getUnlockedAchievements(): List<JsonObject> {
        val player = requirePlayer()
        return player.achievements.map { (id, stored) ->
            val fields = LinkedHashMap<String, JsonElement>()
            achievements[id]?.let {
                fields["name"] = JsonPrimitive(it.name)
                fields["description"] = JsonPrimitive(it.description)
                fields["icon"] = JsonPrimitive(it.icon)
                fields["xp"] = number(it.xp)
            }
            fields["id"] = JsonPrimitive(id)
            fields["unlockedAt"] = number(stored.unlockedAt)
            fields["seen"] = JsonPrimitive(stored.seen)
            fields["reward"] = number(stored.reward)
            JsonObject(fields)
        }
    }

    /** The unlocked achievements the player has not seen yet. */
    public fun getUnseenAchievements(): List<JsonObject> =
        getUnlockedAchievements().filter { (it["seen"] as? JsonPrimitive)?.content != "true" }

    /**
     * Marks the unlocked achievements in [ids] as seen. An id that is not unlocked is ignored.
     * Before [init] it throws [IllegalStateException].
     */
    public suspend fun markAchievementsSeen(ids: Collection<String>) {
        val player = requirePlayer()
        for (id in ids) {
            val stored = player.achievements[id] ?: continue
            player.achievements[id] = Achievement(stored.unlockedAt, true, stored.reward)
        }
        save()
    }

    // A reason that is not a string is kept as its JSON text, as the event field is a String.
    private fun reasonText(reason: JsonElement?): String = when {
        reason == null -> ""
        reason is JsonPrimitive && reason.isString -> reason.content
        else -> reason.toString()
    }

    private fun describe(value: JsonElement?): String = when (value) {
        null -> "undefined"
        is JsonNull -> "null"
        else -> value.toString()
    }
}
