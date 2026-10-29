package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException

/**
 * Something that happened to the player. [wireName] is the event name other ports use, and the
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
        val totalXP: Double,
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
