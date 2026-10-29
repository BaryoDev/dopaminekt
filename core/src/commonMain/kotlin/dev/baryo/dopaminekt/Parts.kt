package dev.baryo.dopaminekt

/**
 * Feedback for the player's hands. Where the device cannot vibrate, an implementation does nothing.
 *
 * An override cannot redeclare default parameter values. Calls through the interface use the
 * defaults declared here.
 */
public interface Haptics {
    /** A 10 ms buzz. */
    public fun tap()

    /** Buzzes 10, 40, 10 ms. */
    public fun double()

    /** Turns buzzing on or off. */
    public fun setEnabled(enabled: Boolean)
}

/**
 * Plays sounds by key.
 *
 * The JS player can also register a synth for a key. That takes a web audio object, so it is not
 * part of this contract. [play] returns nothing here, where JS returns an unspecified value.
 */
public interface SoundPlayer {
    /** Plays the sound for [key]. An unknown key does nothing. */
    public fun play(key: String)

    /** Releases what the player holds. */
    public fun destroy()
}

/** A point on screen. Units belong to the renderer. */
public data class Point(val x: Double, val y: Double)

/** The kinds of burst a [ParticleRenderer] knows. [wireName] is the JS name. */
public enum class BurstKind(public val wireName: String) {
    Confetti("confetti"),
    Sparkle("sparkle"),
    Fire("fire"),
    Stars("stars"),
    Coins("coins");

    public companion object {
        /** The kind named [wireName], or null for an unknown one. */
        public fun fromWireName(wireName: String): BurstKind? = entries.firstOrNull { it.wireName == wireName }
    }
}

/** Tuning for one burst. A null field means the renderer's default. */
public data class BurstOptions(val count: Int? = null, val color: String? = null)

/**
 * Draws bursts of particles.
 *
 * An override cannot redeclare default parameter values. Calls through the interface use the
 * defaults declared here.
 */
public interface ParticleRenderer {
    /**
     * Draws a burst of [kind] at [at] (the renderer picks a place when null), tuned by [options].
     * A kind the renderer does not know does nothing.
     */
    public fun burst(kind: BurstKind, at: Point? = null, options: BurstOptions? = null)

    /** Releases what the renderer holds. */
    public fun destroy()
}

/**
 * What the heads-up display shows. [progress] is the position within the current level, 0 to 1,
 * and [next] is the XP total that marks the next level. Both are null when not known.
 */
public data class HudState(
    val xp: Double,
    val level: Long,
    val streak: Long,
    val progress: Double? = null,
    val next: Double? = null,
)

/** The kinds of popup. [wireName] is the JS name. */
public enum class PopupKind(public val wireName: String) {
    Achievement("achievement"),
    Level("level"),
    Notice("notice"),
    Custom("custom");

    public companion object {
        /** The kind named [wireName], or null for an unknown one. */
        public fun fromWireName(wireName: String): PopupKind? = entries.firstOrNull { it.wireName == wireName }
    }
}

/** How rare an achievement popup looks. [wireName] is the JS name. */
public enum class PopupTier(public val wireName: String) {
    Common("common"),
    Rare("rare"),
    Legendary("legendary");

    public companion object {
        /** The tier named [wireName], or null for an unknown one. */
        public fun fromWireName(wireName: String): PopupTier? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * A popup. [title] is the achievement name, the notice message or the level-up heading. [body] is
 * the achievement description, or the new level for [PopupKind.Level]. [meta] is an extra line
 * under an achievement, such as `+50 XP`. [icon] is text only here. JS also takes a DOM node.
 */
public data class Popup(
    val kind: PopupKind,
    val title: String? = null,
    val body: String? = null,
    val icon: String? = null,
    val meta: String? = null,
    val tier: PopupTier? = null,
)

/** Text that floats up from a place. [size] is a CSS font size such as `32px`. */
public data class FloatingText(
    val text: String,
    val x: Double? = null,
    val y: Double? = null,
    val color: String? = null,
    val size: String? = null,
)

/** The reward display: heads-up numbers, popups and floating text. */
public interface RewardUi {
    /** Shows the heads-up numbers in [state]. */
    public fun hud(state: HudState)

    /** Shows [popup]. */
    public fun popup(popup: Popup)

    /** Shows [text] floating up. */
    public fun text(text: FloatingText)

    /** Releases what the display holds. */
    public fun destroy()
}

/** The parts a plugin can ask for. [wireName] is the JS name. */
public enum class PartName(public val wireName: String) {
    Ui("ui"),
    Particles("particles"),
    Sound("sound"),
    Haptics("haptics");

    public companion object {
        /** The part named [wireName], or null for an unknown one. */
        public fun fromWireName(wireName: String): PartName? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * The parts the app has set. A part that is not set is null. The values are live: read them at
 * call time and do not keep them.
 */
public interface Parts {
    public val ui: RewardUi?
    public val particles: ParticleRenderer?
    public val sound: SoundPlayer?
    public val haptics: Haptics?
}
