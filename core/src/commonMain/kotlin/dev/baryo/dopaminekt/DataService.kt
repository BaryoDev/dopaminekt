package dev.baryo.dopaminekt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** The key prefix the data service uses unless the caller picks one. */
internal const val DEFAULT_PREFIX: String = "dopamine_"

/** The text under a key is not valid JSON. [raw] is the text as read. */
internal class CorruptDataException(val key: String, val raw: String) :
    RuntimeException("saved \"$key\" is not valid JSON") {
    val code: String = CORRUPT_CODE
}

internal const val CORRUPT_CODE: String = "DOPAMINE_CORRUPT"

/**
 * Saves and loads JSON text through a [StoragePort] under a key prefix.
 *
 * Storage calls run one at a time. A load waits for the writes asked for before it, so it reads
 * what they saved. A write that fails does not block the ones after it.
 */
internal class DataService(
    private val storage: StoragePort,
    prefix: String = DEFAULT_PREFIX,
    private val onError: (Throwable) -> Unit = {},
) {
    /** The key prefix. An empty one falls back to the default, as the JS config does. */
    val prefix: String = prefix.ifEmpty { DEFAULT_PREFIX }

    private val lock = Mutex()

    /** Saves [data] as JSON text. Returns false when serializing or the storage call fails. */
    suspend fun save(key: String, data: JsonElement): Boolean {
        return try {
            val text = data.toString()
            lock.withLock { storage.set(prefix + key, text) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            false
        }
    }

    /**
     * Loads the value under [key]. Nothing saved, an empty text, or a saved `null` gives null.
     *
     * With [strict] a failed read throws what the storage threw, and text that is not JSON throws
     * [CorruptDataException]. Without it both give null.
     */
    suspend fun load(key: String, strict: Boolean = false): JsonElement? {
        val text = try {
            lock.withLock { storage.get(prefix + key) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (strict) throw e
            onError(e)
            return null
        }
        if (text.isNullOrEmpty()) return null
        return try {
            parseJsonStrict(text).takeIf { it !is JsonNull }
        } catch (e: Exception) {
            if (strict) throw CorruptDataException(key, text)
            onError(e)
            null
        }
    }

    /** Removes [key]. A failed storage call throws. */
    suspend fun clear(key: String) {
        lock.withLock { storage.remove(prefix + key) }
    }
}
