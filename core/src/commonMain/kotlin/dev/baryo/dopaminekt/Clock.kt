package dev.baryo.dopaminekt

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The only source of time. [localDate] is the local calendar day as
 * `YYYY-MM-DD`, never UTC. A test passes a clock it controls.
 */
public interface Clock {
    /** The time in epoch milliseconds. */
    public fun now(): Long

    /** The local calendar day of [millis] (the current time by default) as `YYYY-MM-DD`. */
    public fun localDate(millis: Long = now()): String
}

/** A [Clock] that reads the system time and names days in [zone]. */
public class SystemClock(
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : Clock {
    override fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    override fun localDate(millis: Long): String {
        val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
        return date.year.toString().padStart(4, '0') + "-" +
            date.month.number.toString().padStart(2, '0') + "-" +
            date.day.toString().padStart(2, '0')
    }
}
