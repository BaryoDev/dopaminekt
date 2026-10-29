package dev.baryo.dopaminekt

// Calendar days are plain integers (days since 1970-01-01), so no zone or
// daylight saving change can move them.

private fun floorDiv(a: Long, b: Long): Long = a.floorDiv(b)

private fun daysFromCivil(year: Long, month: Long, day: Long): Long {
    val y = if (month <= 2) year - 1 else year
    val era = floorDiv(y, 400)
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

private fun dayNum(text: String): Long {
    val year = text.substring(0, 4).toLong()
    val month = text.substring(5, 7).toLong()
    val day = text.substring(8).toLong()
    // Out of range months and days roll over, as a date does, so isDay can compare the text back.
    val months = year * 12 + (month - 1)
    val y = floorDiv(months, 12)
    val m = months - y * 12 + 1
    return daysFromCivil(y, m, 1) + (day - 1)
}

private fun dayText(num: Long): String {
    val z = num + 719468
    val era = floorDiv(z, 146097)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    val head = if (year in 0..9999) pad(year, 4) else (if (year < 0) "-" else "+") + pad(kotlin.math.abs(year), 6)
    return (head + "-" + pad(month, 2) + "-" + pad(day, 2)).take(10)
}

private fun looksLikeDay(text: String): Boolean {
    if (text.length != 10) return false
    for (i in text.indices) {
        val c = text[i]
        if (i == 4 || i == 7) {
            if (c != '-') return false
        } else if (c !in '0'..'9') {
            return false
        }
    }
    return true
}

/** A real calendar day written YYYY-MM-DD. 2026-02-30 and 9999-99-99 are not days; year 0000 is. */
internal fun isDay(text: String?): Boolean = text != null && looksLikeDay(text) && dayText(dayNum(text)) == text

internal fun shiftDay(text: String, days: Int): String = dayText(dayNum(text) + days)

/**
 * Sorted and unique. With [today], nothing older than 400 days counting today
 * stays, and nothing more than a day ahead (a zone can put the player a day
 * past the clock). The window comes from today, never from the newest entry.
 * Without [today] nothing is dropped.
 */
internal fun trimDays(days: Collection<String>, today: String?): List<String> {
    val sorted = days.toSet().sorted()
    if (today == null) return sorted
    val floor = dayNum(today) - 399
    val ceiling = dayNum(today) + 1
    return sorted.filter { dayNum(it) in floor..ceiling }
}

/**
 * The run of consecutive days ending [today], or ending yesterday when today
 * has no play yet. Days after today are ignored.
 */
internal fun currentStreak(days: Collection<String>, today: String): Int {
    val set = days.toHashSet()
    var at = if (today in set) today else shiftDay(today, -1)
    var count = 0
    while (at in set) {
        count++
        at = shiftDay(at, -1)
    }
    return count
}

internal fun longestRun(days: Collection<String>): Int {
    var best = 0
    var run = 0
    var previous: Long? = null
    for (day in days.sorted()) {
        val num = dayNum(day)
        run = if (previous != null && num == previous + 1) run + 1 else 1
        previous = num
        if (run > best) best = run
    }
    return best
}

/** The latest play day that is not after [today], or null. */
internal fun lastDayUntil(days: Collection<String>, today: String): String? =
    days.fold(null as String?) { last, day -> if (day <= today && (last == null || day > last)) day else last }
