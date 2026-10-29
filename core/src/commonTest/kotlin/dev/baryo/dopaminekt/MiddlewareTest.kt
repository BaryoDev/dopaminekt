package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MiddlewareTest {
    private suspend fun MiddlewareChain.xp(amount: Double): BeforeXpContext? =
        before(BeforeXpContext(amount, "r"), ::acceptXp) { m, c -> m.beforeXp(c) }

    @Test
    fun noMiddlewareLeavesTheContextAlone() = runTest {
        assertEquals(BeforeXpContext(5.0, "r"), MiddlewareChain().xp(5.0))
    }

    @Test
    fun hooksRunInUseOrderAndEachSeesThePatchBefore() = runTest {
        val chain = MiddlewareChain()
        val seen = mutableListOf<Double>()
        chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> {
                seen += context.amount
                return Verdict.Patch(context.copy(amount = context.amount * 2))
            }
        })
        chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> {
                seen += context.amount
                return Verdict.Continue
            }
        })
        assertEquals(10.0, chain.xp(5.0)?.amount)
        assertEquals(listOf(5.0, 10.0), seen)
    }

    @Test
    fun rejectStopsTheChain() = runTest {
        val chain = MiddlewareChain()
        var later = 0
        chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> = Verdict.Reject
        })
        chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> {
                later++
                return Verdict.Continue
            }
        })
        assertNull(chain.xp(5.0))
        assertEquals(0, later)
    }

    @Test
    fun aPatchThatFailsAcceptIsDropped() = runTest {
        val chain = MiddlewareChain()
        chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> =
                Verdict.Patch(context.copy(amount = Double.NaN))
        })
        assertEquals(5.0, chain.xp(5.0)?.amount)
    }

    @Test
    fun aThrowingHookIsSkippedAndReported() = runTest {
        val errors = mutableListOf<Throwable>()
        val chain = MiddlewareChain { errors += it }
        chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> = throw IllegalStateException("x")
        })
        chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> =
                Verdict.Patch(context.copy(amount = 7.0))
        })
        assertEquals(7.0, chain.xp(5.0)?.amount)
        assertEquals(1, errors.size)
    }

    @Test
    fun cancellationIsRethrownFromBeforeAndAfter() = runTest {
        val chain = MiddlewareChain()
        chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> = throw CancellationException("stop")
            override suspend fun afterXp(context: AfterXpContext) = throw CancellationException("stop")
        })
        assertFailsWith<CancellationException> { chain.xp(5.0) }
        assertFailsWith<CancellationException> {
            chain.after(AfterXpContext(1.0, "r", 1.0, 1)) { m, c -> m.afterXp(c) }
        }
    }

    @Test
    fun anAfterHookThatThrowsDoesNotStopTheNext() = runTest {
        val chain = MiddlewareChain()
        var ran = 0
        chain.use(object : Middleware {
            override suspend fun afterRecord(context: AfterRecordContext) = throw IllegalStateException("x")
        })
        chain.use(object : Middleware {
            override suspend fun afterRecord(context: AfterRecordContext) {
                ran++
            }
        })
        chain.after(AfterRecordContext("tap", JsonObject(emptyMap()), 10.0)) { m, c -> m.afterRecord(c) }
        assertEquals(1, ran)
    }

    @Test
    fun unsubscribeRemovesTheMiddleware() = runTest {
        val chain = MiddlewareChain()
        val off = chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> = Verdict.Reject
        })
        assertNull(chain.xp(1.0))
        off()
        assertEquals(1.0, chain.xp(1.0)?.amount)
    }

    @Test
    fun theListIsCopiedBeforeARun() = runTest {
        val chain = MiddlewareChain()
        var added = 0
        chain.use(object : Middleware {
            override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> {
                chain.use(object : Middleware {
                    override suspend fun beforeXp(context: BeforeXpContext): Verdict<BeforeXpContext> {
                        added++
                        return Verdict.Continue
                    }
                })
                return Verdict.Continue
            }
        })
        chain.xp(1.0)
        assertEquals(0, added)
    }

    @Test
    fun acceptXpNeedsAFiniteAmount() {
        assertEquals(true, acceptXp(BeforeXpContext(-3.0, "")))
        assertEquals(false, acceptXp(BeforeXpContext(Double.POSITIVE_INFINITY, "")))
        assertEquals(false, acceptXp(BeforeXpContext(Double.NaN, "")))
    }

    @Test
    fun acceptRecordChecksActivityAndScore() {
        fun ctx(activity: String, result: JsonObject) = BeforeRecordContext(activity, result)
        assertEquals(true, acceptRecord(ctx("tap", JsonObject(emptyMap()))))
        assertEquals(true, acceptRecord(ctx("tap", JsonObject(mapOf("score" to JsonPrimitive(5))))))
        assertEquals(false, acceptRecord(ctx("__proto__", JsonObject(emptyMap()))))
        assertEquals(false, acceptRecord(ctx("", JsonObject(emptyMap()))))
        assertEquals(false, acceptRecord(ctx("tap", JsonObject(mapOf("score" to JsonPrimitive("50"))))))
        assertEquals(false, acceptRecord(ctx("tap", JsonObject(mapOf("score" to JsonNull)))))
    }

    @Test
    fun acceptUnlockNeedsAFiniteRewardOfZeroOrMore() {
        assertEquals(true, acceptUnlock(BeforeUnlockContext("a", 0.0)))
        assertEquals(true, acceptUnlock(BeforeUnlockContext("a", 50.0)))
        assertEquals(false, acceptUnlock(BeforeUnlockContext("a", -1.0)))
        assertEquals(false, acceptUnlock(BeforeUnlockContext("a", Double.NaN)))
        assertEquals(false, acceptUnlock(BeforeUnlockContext("a", Double.POSITIVE_INFINITY)))
    }

    @Test
    fun defaultHooksDoNothing() = runTest {
        val noop = object : Middleware {}
        assertEquals(Verdict.Continue, noop.beforeXp(BeforeXpContext(1.0, "")))
        assertEquals(Verdict.Continue, noop.beforeRecord(BeforeRecordContext("a", JsonObject(emptyMap()))))
        assertEquals(Verdict.Continue, noop.beforeUnlock(BeforeUnlockContext("a", 1.0)))
        noop.afterXp(AfterXpContext(1.0, "", 1.0, 1))
        noop.afterUnlock(AfterUnlockContext("a", 1.0))
    }
}
