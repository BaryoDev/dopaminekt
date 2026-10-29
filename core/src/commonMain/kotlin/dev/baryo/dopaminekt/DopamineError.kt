package dev.baryo.dopaminekt

/**
 * Thrown when a saved document cannot be used, or when a plugin breaks a rule.
 *
 * [code] is one of the document codes `invalid_document` (the value is not an object),
 * `unsupported_version` (the `v` field is not 3) and `too_many_devices` (more than 20 devices
 * across the per-device maps), or one of the plugin codes:
 *
 * - `plugin_api`: the plugin's `api` is not [HOST_API].
 * - `plugin_order`: a plugin named in `after` is not installed yet.
 * - `plugin_requires`: a part named in `requires` is not set.
 * - `plugin_invalid`: the plugin is malformed, for example a name that fails [isValidPluginName].
 * - `plugin_duplicate`: a plugin with the same name is already installed.
 * - `plugin_event`: an event name is not `<plugin name>:<event>`.
 * - `invalid_progress`: the progress object given to the app is not usable.
 * - `disposed`: the call came after the app was disposed.
 *
 * Codes are plain strings, so a new one is not a breaking change. Core itself throws only the
 * document codes and `plugin_event`. The rest are for whoever implements plugin installs.
 */
public class DopamineError(
    public val code: String,
    message: String = code,
) : RuntimeException(message)
