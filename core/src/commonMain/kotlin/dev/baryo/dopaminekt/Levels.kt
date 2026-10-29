package dev.baryo.dopaminekt

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** The level for [xp]. The curve is XP = 50 * level * (level - 1), so 0 XP is level 1. */
public fun levelFor(xp: Double): Long =
    if (xp > 0) max(1L, floor((1 + sqrt(1 + 4 * xp / 50)) / 2).toLong()) else 1L

/** The XP at which [level] starts. */
public fun thresholdFor(level: Long): Double = 50.0 * level * (level - 1)
