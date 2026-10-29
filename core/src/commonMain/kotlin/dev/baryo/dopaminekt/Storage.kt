package dev.baryo.dopaminekt

/**
 * Where a caller keeps saved text. Each call may suspend, so a database or a
 * file works as well as memory.
 */
public interface StoragePort {
    /** The text saved under [key], or null when there is none. */
    public suspend fun get(key: String): String?

    /** Saves [value] under [key]. */
    public suspend fun set(key: String, value: String)

    /** Removes [key]. Removing a key that is not there is not an error. */
    public suspend fun remove(key: String)
}

/** A [StoragePort] kept in memory. Values do not survive the process. */
public class MemoryStorage : StoragePort {
    private val values = LinkedHashMap<String, String>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun set(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    /** Removes every key. */
    public fun clear() {
        values.clear()
    }
}
