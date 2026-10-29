package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Something that happened to the player. [wireName] is the event name the JS library emits, and the
 * fields carry the same keys as their payloads.
 */
public sealed class ProgressEvent(public val wireName: String) {
    /** XP changed. [amount] is negative when XP was spent. */
    public data class XpGained(
        val amount: Double,
        val reason: String,
        val leveledUp: Boolean,
        val newLevel: Long,
        val total: Double,
    ) : ProgressEvent("xp_gained")

    /** The level went up. */
    public data class LevelUp(
        val oldLevel: Long,
        val newLevel: Long,
        val totalXp: Double,
    ) : ProgressEvent("level_up")

    /** An achievement unlocked. The fields are the definition's, without its check. */
    public data class AchievementUnlocked(
        val id: String,
        val name: String,
        val description: String,
        val icon: String,
        val xp: Double,
    ) : ProgressEvent("achievement_unlocked")

    /** A new play day changed the streak. */
    public data class StreakUpdated(
        val current: Long,
        val longest: Long,
    ) : ProgressEvent("streak_updated")

    /** A score beat the best for [activity]. */
    public data class PersonalBest(
        val activity: String,
        val score: Double,
    ) : ProgressEvent("personal_best")

    /** The player was reset. */
    public data object PlayerReset : ProgressEvent("player_reset")

    /** Saving [key] failed. */
    public data class SaveFailed(
        val key: String,
    ) : ProgressEvent("save_failed")

    /**
     * Loading [key] failed. [reason] is `corrupt`, `read`, `too_many_devices` or the code of the
     * [DopamineError]. [backedUp] is set for `corrupt`. [error] is set for `read` and error codes.
     */
    public data class LoadFailed(
        val key: String,
        val reason: String,
        val backedUp: Boolean? = null,
        val error: Throwable? = null,
    ) : ProgressEvent("load_failed")

    /**
     * An event a plugin emits. [wireName] is `<plugin name>:<event>` and [data] is whatever the
     * plugin sends, or null for none. The constructor is public so a caller can build one, but only
     * [Progress.emit] checks the name. Core event names never reach a listener through it.
     */
    public class Custom(wireName: String, public val data: JsonElement? = null) : ProgressEvent(wireName)
}

/**
 * Delivers events to listeners by wire name, in the order they were added.
 *
 * Emit is synchronous. A throwing listener is passed to [onError] and skipped. The listeners are
 * copied before each emit, so one added during an emit is not called for that emit. The same
 * listener added twice for one name is kept once.
 */
internal class EventEmitter(private val onError: (Throwable) -> Unit = {}) {
    private val listeners = LinkedHashMap<String, LinkedHashSet<(ProgressEvent) -> Unit>>()

    /** Adds [listener] for [wireName]. Returns a function that removes it. */
    fun on(wireName: String, listener: (ProgressEvent) -> Unit): () -> Unit {
        listeners.getOrPut(wireName) { LinkedHashSet() }.add(listener)
        return { off(wireName, listener) }
    }

    fun off(wireName: String, listener: (ProgressEvent) -> Unit) {
        listeners[wireName]?.remove(listener)
    }

    fun emit(event: ProgressEvent) {
        val copy = listeners[event.wireName]?.toList() ?: return
        for (listener in copy) {
            try {
                listener(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    fun clear() {
        listeners.clear()
    }
}

/**
 * The payload the JS library publishes with this event, under the JS keys (`totalXP`, not
 * `totalXp`). A load error is written as its message. The achievement check is not part of it.
 * A core event gives a [JsonObject]. A [ProgressEvent.Custom] gives its data unchanged, which is
 * null for none.
 */
internal fun ProgressEvent.payload(): JsonElement? {
    if (this is ProgressEvent.Custom) return data
    val fields = LinkedHashMap<String, JsonElement>()
    when (this) {
        is ProgressEvent.XpGained -> {
            fields["amount"] = number(amount)
            fields["reason"] = JsonPrimitive(reason)
            fields["leveledUp"] = JsonPrimitive(leveledUp)
            fields["newLevel"] = JsonPrimitive(newLevel)
            fields["total"] = number(total)
        }
        is ProgressEvent.LevelUp -> {
            fields["oldLevel"] = JsonPrimitive(oldLevel)
            fields["newLevel"] = JsonPrimitive(newLevel)
            fields["totalXP"] = number(totalXp)
        }
        is ProgressEvent.AchievementUnlocked -> {
            fields["name"] = JsonPrimitive(name)
            fields["description"] = JsonPrimitive(description)
            fields["icon"] = JsonPrimitive(icon)
            fields["xp"] = number(xp)
            fields["id"] = JsonPrimitive(id)
        }
        is ProgressEvent.StreakUpdated -> {
            fields["current"] = JsonPrimitive(current)
            fields["longest"] = JsonPrimitive(longest)
        }
        is ProgressEvent.PersonalBest -> {
            fields["activity"] = JsonPrimitive(activity)
            fields["score"] = number(score)
        }
        is ProgressEvent.PlayerReset -> {}
        is ProgressEvent.SaveFailed -> fields["key"] = JsonPrimitive(key)
        is ProgressEvent.Custom -> {}
        is ProgressEvent.LoadFailed -> {
            fields["key"] = JsonPrimitive(key)
            fields["reason"] = JsonPrimitive(reason)
            backedUp?.let { fields["backedUp"] = JsonPrimitive(it) }
            error?.let { fields["error"] = JsonPrimitive(it.message ?: it.toString()) }
        }
    }
    return JsonObject(fields)
}
