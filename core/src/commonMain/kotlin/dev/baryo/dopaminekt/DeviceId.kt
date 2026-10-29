package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/** The key the device id is saved under. */
internal const val DEVICE_KEY: String = "device"

/** A device id and whether it is already saved. */
internal class DeviceId(val id: String, val saved: Boolean)

/** A stored id must be a safe key and not the legacy name. */
internal fun isDeviceId(id: String?): Boolean = id != null && isSafeKey(id) && id != LEGACY

/**
 * Sixteen lowercase hex characters from random bytes. The bytes come from a random UUID with the
 * version and variant bytes (6 and 8) left out, so every bit is random.
 */
internal fun randomDeviceId(): String {
    val bytes = Uuid.random().toByteArray()
    return listOf(0, 1, 2, 3, 4, 5, 7, 9).joinToString("") { (bytes[it].toInt() and 0xff).toString(16).padStart(2, '0') }
}

/**
 * Reads the device id. A stored id that is valid is kept and counts as saved. Anything else, an
 * unreadable or corrupt value included, gives a fresh id that is not saved yet.
 */
internal suspend fun DataService.readDevice(create: () -> String = ::randomDeviceId): DeviceId {
    val stored = try {
        load(DEVICE_KEY, strict = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return DeviceId(create(), saved = false)
    }
    val text = (stored as? JsonPrimitive)?.takeIf { it.isString }?.content
    return if (isDeviceId(text)) DeviceId(text!!, saved = true) else DeviceId(create(), saved = false)
}

/** Saves [id] as the device id. Returns whether it worked. */
internal suspend fun DataService.saveDevice(id: String): Boolean = save(DEVICE_KEY, JsonPrimitive(id))
