package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** What a before hook decides: carry on, carry on with a changed context, or reject. */
public sealed interface Verdict<out C> {
    /** Leave the context as it is. */
    public data object Continue : Verdict<Nothing>

    /** Stop the action. The after hooks do not run. */
    public data object Reject : Verdict<Nothing>

    /**
     * Carry on with [context] in place of the one given. The change is dropped when the new
     * context is not acceptable: an amount that is not finite, a record with an unsafe activity
     * or a score that is not a finite number, or a reward that is not finite or is below 0.
     */
    public data class Patch<C>(val context: C) : Verdict<C>
}

/** Before XP is added. */
public data class BeforeXpContext(val amount: Double, val reason: String)

/** After XP was added. [amount] is what was applied, [total] and [level] the new state. */
public data class AfterXpContext(val amount: Double, val reason: String, val total: Double, val level: Long)

/** Before a play is recorded. [result] is the result object the caller gave. */
public data class BeforeRecordContext(val activity: String, val result: JsonObject)

/** After a play was recorded. [xp] is the XP the play earned. */
public data class AfterRecordContext(val activity: String, val result: JsonObject, val xp: Double)

/** Before an achievement unlocks. [reward] is its XP. */
public data class BeforeUnlockContext(val id: String, val reward: Double)

/** After an achievement unlocked. */
public data class AfterUnlockContext(val id: String, val reward: Double)

/**
 * Hooks around the actions of a player. Every hook does nothing by default, so an app overrides
 * the ones it needs. Hooks run in the order the middleware was added. A hook that throws is
 * skipped, except for a cancellation, which is rethrown.
 */
public interface Middleware {
    public suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> = Verdict.Continue

    public suspend fun afterXp(context: AfterXpContext) {}

    public suspend fun beforeRecord(context: BeforeRecordContext): Verdict<BeforeRecordContext> = Verdict.Continue

    public suspend fun afterRecord(context: AfterRecordContext) {}

    public suspend fun beforeUnlock(context: BeforeUnlockContext): Verdict<BeforeUnlockContext> = Verdict.Continue

    public suspend fun afterUnlock(context: AfterUnlockContext) {}
}

internal fun acceptXp(context: BeforeXpContext): Boolean = context.amount.isFinite()

internal fun acceptRecord(context: BeforeRecordContext): Boolean {
    if (!isSafeKey(context.activity)) return false
    val score: JsonElement = context.result["score"] ?: return true
    return score.finiteNumber() != null
}

internal fun acceptUnlock(context: BeforeUnlockContext): Boolean =
    context.reward.isFinite() && context.reward >= 0

/** The middleware in use order. The list is copied before each run. */
internal class MiddlewareChain(private val onError: (Throwable) -> Unit = {}) {
    private val list = ArrayList<Middleware>()

    /** Adds [middleware]. Returns a function that removes it. */
    fun use(middleware: Middleware): () -> Unit {
        list.add(middleware)
        return {
            val at = list.indexOf(middleware)
            if (at >= 0) list.removeAt(at)
        }
    }

    /**
     * Runs a before phase. Returns the context after the patches, or null when a hook rejected.
     * A patch is kept only when [accept] passes.
     */
    suspend fun <C> before(
        context: C,
        accept: (C) -> Boolean,
        hook: suspend (Middleware, C) -> Verdict<C>,
    ): C? {
        var current = context
        for (middleware in list.toList()) {
            val verdict = try {
                hook(middleware, current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                continue
            }
            when (verdict) {
                is Verdict.Reject -> return null
                is Verdict.Patch -> if (accept(verdict.context)) current = verdict.context
                is Verdict.Continue -> {}
            }
        }
        return current
    }

    suspend fun <C> after(context: C, hook: suspend (Middleware, C) -> Unit) {
        for (middleware in list.toList()) {
            try {
                hook(middleware, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
