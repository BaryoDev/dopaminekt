package dev.baryo.dopaminekt

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement

/** The plugin API version this build accepts. A plugin's [DopaminePlugin.api] must equal it. */
public const val HOST_API: Int = 3

/** Undoes an install. */
public fun interface Disposer {
    /** Releases what the plugin set up. */
    public fun dispose()
}

/** True when [name] is a valid plugin name: one or more of `a` to `z`, `0` to `9` and `-`. */
public fun isValidPluginName(name: String): Boolean =
    name.isNotEmpty() && name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

/**
 * Something that adds to the app without being part of core. Core defines the contract and ships
 * no implementation of it.
 */
public interface DopaminePlugin {
    /** The plugin's name. It must pass [isValidPluginName] and be unique. */
    public val name: String

    /** The plugin's own version text. */
    public val version: String

    /** The plugin API version the plugin was written for. It must equal [HOST_API]. */
    public val api: Int

    /** Names of plugins that must be installed and finished before this one. */
    public val after: List<String> get() = emptyList()

    /** Parts that must not be null when this plugin installs. */
    public val requires: List<PartName> get() = emptyList()

    /** Sets the plugin up. Returns what undoes it, or null when there is nothing to undo. */
    public suspend fun install(context: PluginContext): Disposer?
}

/**
 * Where a plugin writes its logs.
 *
 * Takes one message and an optional [Throwable]. JS takes any number of arguments.
 */
public interface Logger {
    public fun debug(message: String, error: Throwable? = null)
    public fun info(message: String, error: Throwable? = null)
    public fun warn(message: String, error: Throwable? = null)
    public fun error(message: String, error: Throwable? = null)
}

/** What the plugin can learn about where it runs. */
public interface Env {
    /** True when the user asked for less motion. */
    public val reducedMotion: Boolean
}

/**
 * The event bus as a plugin sees it. Events are [ProgressEvent]s, so a plugin hears core events
 * and the [ProgressEvent.Custom] events of plugins.
 *
 * There is no `off`. [on] returns the remover, and whoever implements this also removes every
 * listener when the plugin is disposed, even if the plugin forgets.
 */
public interface PluginEvents {
    /** Calls [listener] for each event named [wireName]. Returns a function that removes it. */
    public fun on(wireName: String, listener: (ProgressEvent) -> Unit): () -> Unit

    /**
     * Emits a [ProgressEvent.Custom] with [data]. [wireName] must be `<plugin name>:<event>`, with
     * the name of the plugin that owns this bus. Anything else throws [DopamineError] with the
     * code `plugin_event`. That check belongs to the implementer.
     */
    public fun emit(wireName: String, data: JsonElement? = null)
}

/**
 * What a plugin gets when it installs.
 *
 * - [storage]: every key is prefixed `dopamine_plugin_<name>:`, exactly as in JS, so data written
 *   by either port reads in the other.
 * - [scope]: cancelled when the plugin is disposed. This is the one addition over JS.
 * - [parts]: live, so read a part at call time. A part named in [DopaminePlugin.requires] is not
 *   null at install.
 * - [plugin]: returns null until the named plugin has finished installing, which is what
 *   [DopaminePlugin.after] waits for.
 */
public interface PluginContext {
    public val progress: Progress
    public val parts: Parts
    public val events: PluginEvents
    public val storage: StoragePort
    public val clock: Clock
    public val log: Logger
    public val env: Env
    public val scope: CoroutineScope

    /** The handle the plugin called [name] passed to [expose], or null. */
    public fun plugin(name: String): Any?

    /** Makes [handle] available to other plugins through [plugin]. */
    public fun expose(handle: Any?)
}
