package dev.baryo.dopaminekt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceIdTest {
    @Test
    fun aNewIdIsSixteenLowerCaseHexCharacters() {
        repeat(50) {
            val id = randomDeviceId()
            assertEquals(16, id.length)
            assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' }, id)
        }
    }

    @Test
    fun newIdsDiffer() {
        assertEquals(100, List(100) { randomDeviceId() }.toSet().size)
    }

    @Test
    fun aStoredValidIdIsKeptAndCountsAsSaved() = runTest {
        val storage = MemoryStorage()
        storage.set("dopamine_device", "\"abc123\"")
        val device = DataService(storage).readDevice { "fresh" }
        assertEquals("abc123", device.id)
        assertTrue(device.saved)
    }

    @Test
    fun nothingStoredMakesAnUnsavedId() = runTest {
        val device = DataService(MemoryStorage()).readDevice { "fresh" }
        assertEquals("fresh", device.id)
        assertFalse(device.saved)
    }

    @Test
    fun legacyAndUnsafeAndNonStringIdsAreReplaced() = runTest {
        for (stored in listOf("\"legacy\"", "\"__proto__\"", "\"constructor\"", "\"prototype\"", "\"\"", "5", "null", "[\"a\"]", "{\"a\":1}")) {
            val storage = MemoryStorage()
            storage.set("dopamine_device", stored)
            val device = DataService(storage).readDevice { "fresh" }
            assertEquals("fresh", device.id, stored)
            assertFalse(device.saved, stored)
        }
    }

    @Test
    fun aCorruptOrUnreadableValueGivesAFreshId() = runTest {
        val corrupt = MemoryStorage()
        corrupt.set("dopamine_device", "{not json")
        assertEquals("fresh", DataService(corrupt).readDevice { "fresh" }.id)

        val bad = object : StoragePort {
            override suspend fun get(key: String): String? = throw IllegalStateException("denied")
            override suspend fun set(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        val device = DataService(bad).readDevice { "fresh" }
        assertEquals("fresh", device.id)
        assertFalse(device.saved)
    }

    @Test
    fun savedIdIsStoredJsonQuoted() = runTest {
        val storage = MemoryStorage()
        val data = DataService(storage)
        assertTrue(data.saveDevice("abc123"))
        assertEquals("\"abc123\"", storage.get("dopamine_device"))
        assertEquals(JsonPrimitive("abc123"), data.load("device"))
        assertNotEquals("legacy", data.readDevice().id)
    }

    @Test
    fun isDeviceIdFollowsTheKeyRule() {
        assertTrue(isDeviceId("a"))
        assertFalse(isDeviceId(null))
        assertFalse(isDeviceId(""))
        assertFalse(isDeviceId("legacy"))
        assertFalse(isDeviceId("__proto__"))
    }
}
