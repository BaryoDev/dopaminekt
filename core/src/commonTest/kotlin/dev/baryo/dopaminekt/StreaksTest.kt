package dev.baryo.dopaminekt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreaksTest {
    @Test
    fun isDayAcceptsRealCalendarDays() {
        assertTrue(isDay("2026-08-07"))
        assertTrue(isDay("2024-02-29"))
        assertTrue(isDay("0000-01-01"))
        assertTrue(isDay("9999-12-31"))
    }

    @Test
    fun isDayRejectsImpossibleDaysAndBadShapes() {
        assertFalse(isDay("2026-02-30"))
        assertFalse(isDay("2025-02-29"))
        assertFalse(isDay("9999-99-99"))
        assertFalse(isDay("2026-00-10"))
        assertFalse(isDay("2026-01-00"))
        assertFalse(isDay("2026-1-1"))
        assertFalse(isDay("2026-08-07T00:00"))
        assertFalse(isDay(null))
    }

    @Test
    fun shiftDayCrossesMonthsYearsAndLeapDays() {
        assertEquals("2024-03-01", shiftDay("2024-02-29", 1))
        assertEquals("2025-03-01", shiftDay("2025-02-28", 1))
        assertEquals("2027-01-01", shiftDay("2026-12-31", 1))
        assertEquals("2026-12-31", shiftDay("2027-01-01", -1))
        assertEquals("2025-07-04", shiftDay("2026-08-07", -399))
    }

    @Test
    fun trimDaysSortsAndRemovesDuplicatesWithoutToday() {
        assertEquals(listOf("2026-01-01", "2026-01-02"), trimDays(listOf("2026-01-02", "2026-01-01", "2026-01-02"), null))
        assertEquals(listOf("1999-01-01", "2099-01-01"), trimDays(listOf("2099-01-01", "1999-01-01"), null))
    }

    @Test
    fun trimDaysKeepsA400DayWindowAndOneDayAhead() {
        val today = "2026-08-07"
        val days = listOf("2025-07-03", "2025-07-04", "2026-08-07", "2026-08-08", "2026-08-09", "2099-01-01")
        assertEquals(listOf("2025-07-04", "2026-08-07", "2026-08-08"), trimDays(days, today))
    }

    @Test
    fun currentStreakCountsDaysEndingTodayOrYesterday() {
        val days = listOf("2026-08-04", "2026-08-05", "2026-08-06")
        assertEquals(3, currentStreak(days, "2026-08-06"))
        assertEquals(3, currentStreak(days, "2026-08-07"))
        assertEquals(0, currentStreak(days, "2026-08-08"))
        assertEquals(0, currentStreak(emptyList(), "2026-08-08"))
    }

    @Test
    fun currentStreakIgnoresDaysAfterToday() {
        assertEquals(0, currentStreak(listOf("2026-08-08"), "2026-08-07"))
        assertEquals(2, currentStreak(listOf("2026-08-06", "2026-08-07", "2026-08-08"), "2026-08-07"))
    }

    @Test
    fun yesterdayIsACalendarDayAcrossADaylightSavingChange() {
        // US clocks went forward on 2026-03-08. Days are integers, so no zone moves them.
        assertEquals("2026-03-08", shiftDay("2026-03-09", -1))
        assertEquals(2, currentStreak(listOf("2026-03-07", "2026-03-08"), "2026-03-09"))
    }

    @Test
    fun longestRunFindsTheBestRun() {
        assertEquals(0, longestRun(emptyList()))
        assertEquals(1, longestRun(listOf("2026-08-01", "2026-08-03")))
        assertEquals(3, longestRun(listOf("2026-02-27", "2026-02-28", "2026-03-01", "2026-03-05")))
        assertEquals(2, longestRun(listOf("2026-12-31", "2027-01-01", "2026-01-01")))
    }

    @Test
    fun lastDayUntilIgnoresDaysAfterToday() {
        assertEquals("2026-08-06", lastDayUntil(listOf("2026-08-05", "2026-08-06", "2026-08-08"), "2026-08-07"))
        assertNull(lastDayUntil(listOf("2026-08-08"), "2026-08-07"))
        assertNull(lastDayUntil(emptyList(), "2026-08-07"))
    }

    @Test
    fun clockNamesTheLocalDayInItsZone() {
        // 2026-03-09 03:00 UTC is still 2026-03-08 in Los Angeles and already 2026-03-09 in Manila.
        val millis = 1_773_025_200_000L
        assertEquals("2026-03-08", SystemClock(kotlinx.datetime.TimeZone.of("America/Los_Angeles")).localDate(millis))
        assertEquals("2026-03-09", SystemClock(kotlinx.datetime.TimeZone.of("Asia/Manila")).localDate(millis))
        assertEquals("2026-03-09", SystemClock(kotlinx.datetime.TimeZone.UTC).localDate(millis))
    }
}
