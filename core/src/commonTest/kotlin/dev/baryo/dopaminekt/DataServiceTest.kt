package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A storage whose calls take [delays] per key in turn and record when they start and finish. */
internal class DelayingStorage(
    private val delays: MutableList<Long> = mutableListOf(),
    private val failures: MutableList<Boolean> = mutableListOf(),
) : StoragePort {
    val values = LinkedHashMap<String, String>()
    val log = mutableListOf<String>()
    var cancelOnSet = false

    override suspend fun get(key: String): String? {
        log += "get:$key"
        return values[key]
    }

    override suspend fun set(key: String, value: String) {
        log += "start:$value"
        if (cancelOnSet) throw CancellationException("stop")
        delay(if (delays.isEmpty()) 0 else delays.removeAt(0))
        if (failures.isNotEmpty() && failures.removeAt(0)) {
            log += "fail:$value"
            throw IllegalStateException("disk full")
        }
        values[key] = value
        log += "end:$value"
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DataServiceTest {
    @Test
    fun savesJsonTextUnderThePrefixedKey() = runTest {
        val storage = MemoryStorage()
        val data = DataService(storage, prefix = "p_")
        val value = JsonObject(mapOf("xp" to JsonPrimitive(100)))
        assertTrue(data.save("player", value))
        assertEquals("""{"xp":100}""", storage.get("p_player"))
        assertEquals(value, data.load("player"))
    }

    @Test
    fun defaultPrefixIsDopamine() = runTest {
        val storage = MemoryStorage()
        DataService(storage).save("device", JsonPrimitive("abc"))
        assertEquals("\"abc\"", storage.get("dopamine_device"))
    }

    @Test
    fun aBackupIsTheQuotedRawText() = runTest {
        val storage = MemoryStorage()
        DataService(storage).save("player_corrupt", JsonPrimitive("{not json"))
        assertEquals("\"{not json\"", storage.get("dopamine_player_corrupt"))
    }

    @Test
    fun nothingSavedAndAnEmptyStringBothLoadAsNull() = runTest {
        val storage = MemoryStorage()
        val data = DataService(storage)
        assertNull(data.load("player", strict = true))
        storage.set("dopamine_player", "")
        assertNull(data.load("player", strict = true))
    }

    @Test
    fun strictLoadThrowsCorruptWithTheRawText() = runTest {
        val storage = MemoryStorage()
        storage.set("dopamine_player", "{not json")
        val error = assertFailsWith<CorruptDataException> { DataService(storage).load("player", strict = true) }
        assertEquals("DOPAMINE_CORRUPT", error.code)
        assertEquals("{not json", error.raw)
        assertEquals("player", error.key)
    }

    @Test
    fun lenientLoadGivesNullForCorruptAndReadFailures() = runTest {
        val seen = mutableListOf<Throwable>()
        val bad = object : StoragePort {
            override suspend fun get(key: String): String? = throw IllegalStateException("denied")
            override suspend fun set(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        assertNull(DataService(bad, onError = { seen += it }).load("player"))
        assertEquals(1, seen.size)

        val storage = MemoryStorage()
        storage.set("dopamine_player", "{not json")
        assertNull(DataService(storage, onError = { seen += it }).load("player"))
        assertEquals(2, seen.size)
    }

    @Test
    fun strictLoadRethrowsAStorageFailure() = runTest {
        val bad = object : StoragePort {
            override suspend fun get(key: String): String? = throw IllegalStateException("denied")
            override suspend fun set(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        assertFailsWith<IllegalStateException> { DataService(bad).load("player", strict = true) }
    }

    @Test
    fun writesRunOneAtATimeInTheOrderAsked() = runTest {
        val storage = DelayingStorage(delays = mutableListOf(100, 10))
        val data = DataService(storage)
        launch { data.save("player", JsonPrimitive("a")) }
        runCurrent()
        launch { data.save("player", JsonPrimitive("b")) }
        advanceUntilIdle()
        assertEquals(
            listOf("start:\"a\"", "end:\"a\"", "start:\"b\"", "end:\"b\""),
            storage.log,
        )
        assertEquals("\"b\"", storage.values["dopamine_player"])
    }

    @Test
    fun aLoadWaitsForEarlierWrites() = runTest {
        val storage = DelayingStorage(delays = mutableListOf(100))
        val data = DataService(storage)
        launch { data.save("player", JsonPrimitive("a")) }
        runCurrent()
        var loaded: Any? = "unset"
        launch { loaded = data.load("player") }
        runCurrent()
        assertEquals("unset", loaded)
        advanceUntilIdle()
        assertEquals(JsonPrimitive("a"), loaded)
    }

    @Test
    fun aFailedWriteDoesNotBlockLaterOnes() = runTest {
        val seen = mutableListOf<Throwable>()
        val storage = DelayingStorage(delays = mutableListOf(50, 5), failures = mutableListOf(true, false))
        val data = DataService(storage, onError = { seen += it })
        var first: Boolean? = null
        var second: Boolean? = null
        launch { first = data.save("player", JsonPrimitive("a")) }
        runCurrent()
        launch { second = data.save("player", JsonPrimitive("b")) }
        advanceUntilIdle()
        assertEquals(false, first)
        assertEquals(true, second)
        assertEquals(1, seen.size)
        assertEquals("\"b\"", storage.values["dopamine_player"])
        assertFalse(storage.values.isEmpty())
    }

    @Test
    fun cancellationFromStorageIsRethrownAndTheLockIsFreed() = runTest {
        val storage = DelayingStorage()
        val data = DataService(storage)
        storage.cancelOnSet = true
        assertFailsWith<CancellationException> { data.save("player", JsonPrimitive("a")) }
        storage.cancelOnSet = false
        assertTrue(data.save("player", JsonPrimitive("b")))
    }

    @Test
    fun clearRemovesTheKey() = runTest {
        val storage = MemoryStorage()
        val data = DataService(storage)
        data.save("player", JsonPrimitive(1))
        data.clear("player")
        assertNull(storage.get("dopamine_player"))
    }
}
