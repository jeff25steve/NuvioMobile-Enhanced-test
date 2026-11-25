package com.nuvio.app.features.whatsnew

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.until
import kotlin.time.Instant

internal enum class LastCheckedUnit {
    JUST_NOW,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR,
}

internal data class LastCheckedAge(
    val amount: Long,
    val unit: LastCheckedUnit,
)

private fun kotlinx.datetime.LocalDate.isLastDayOfMonth(): Boolean {
    val daysInMonth = when (monthNumber) {
        4, 6, 9, 11 -> 30
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        else -> 31
    }
    return dayOfMonth == daysInMonth
}

internal fun calculateLastCheckedAge(
    fetchedAtMillis: Long,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): LastCheckedAge {
    val effectiveNowMillis = nowMillis.coerceAtLeast(fetchedAtMillis)
    val elapsedMillis = effectiveNowMillis - fetchedAtMillis

    val minuteMillis = 60_000L
    val hourMillis = 60L * minuteMillis
    val dayMillis = 24L * hourMillis

    if (elapsedMillis < minuteMillis) {
        return LastCheckedAge(amount = 0L, unit = LastCheckedUnit.JUST_NOW)
    }

    if (elapsedMillis < hourMillis) {
        return LastCheckedAge(
            amount = elapsedMillis / minuteMillis,
            unit = LastCheckedUnit.MINUTE,
        )
    }

    val start = Instant.fromEpochMilliseconds(fetchedAtMillis)
    val end = Instant.fromEpochMilliseconds(effectiveNowMillis)
    // Whole calendar months use the timezone-aware month unit so end-of-month
    // pairs such as Jan 31 -> Feb 28 count as one month, not 28 elapsed days.
    val startLocal = start.toLocalDateTime(timeZone)
    val endLocal = end.toLocalDateTime(timeZone)
    val elapsedCalendarMonths = start.until(end, DateTimeUnit.MONTH, timeZone)
    val monthBoundaryDifference =
        (endLocal.year - startLocal.year) * 12L +
            (endLocal.monthNumber - startLocal.monthNumber).toLong()
    val bothAtMonthEnd = startLocal.date.isLastDayOfMonth() &&
        endLocal.date.isLastDayOfMonth() &&
        endLocal.time >= startLocal.time
    // until(MONTH) can return zero for Jan 31 -> Feb 28 even though both dates
    // are the final day of their respective months. Treat matching month-end
    // boundaries as a full calendar month without rounding arbitrary 28-day spans.
    val wholeCalendarMonths = if (
        bothAtMonthEnd && monthBoundaryDifference > elapsedCalendarMonths
    ) {
        monthBoundaryDifference
    } else {
        elapsedCalendarMonths
    }
    val calendarPeriod = start.periodUntil(end, timeZone)
    val wholeCalendarYears = wholeCalendarMonths / 12L
    val remainingCalendarMonths = wholeCalendarMonths % 12L

    return when {
        wholeCalendarYears >= 1L -> LastCheckedAge(
            amount = wholeCalendarYears,
            unit = LastCheckedUnit.YEAR,
        )
        remainingCalendarMonths >= 1L -> LastCheckedAge(
            amount = remainingCalendarMonths,
            unit = LastCheckedUnit.MONTH,
        )
        calendarPeriod.days >= 7 -> LastCheckedAge(
            amount = (calendarPeriod.days / 7L),
            unit = LastCheckedUnit.WEEK,
        )
        calendarPeriod.days >= 1 -> LastCheckedAge(
            amount = calendarPeriod.days.toLong(),
            unit = LastCheckedUnit.DAY,
        )
        elapsedMillis >= dayMillis -> LastCheckedAge(
            amount = elapsedMillis / dayMillis,
            unit = LastCheckedUnit.DAY,
        )
        else -> LastCheckedAge(
            amount = elapsedMillis / hourMillis,
            unit = LastCheckedUnit.HOUR,
        )
    }
}