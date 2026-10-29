package dev.baryo.dopaminekt

/**
 * Thrown when a saved document cannot be used.
 *
 * [code] is one of `invalid_document` (the value is not an object),
 * `unsupported_version` (the `v` field is not 3) or `too_many_devices`
 * (more than 20 devices across the per-device maps).
 */
public class DopamineError(
    public val code: String,
    message: String = code,
) : RuntimeException(message)
