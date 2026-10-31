package com.nuvio.app.features.whatsnew

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
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
    val calendarPeriod = start.periodUntil(end, timeZone)
    val startLocal = start.toLocalDateTime(timeZone)
    val endLocal = end.toLocalDateTime(timeZone)
    val calendarMonthDelta = (
        (endLocal.year - startLocal.year) * 12 +
            endLocal.monthNumber - startLocal.monthNumber
        ).coerceAtLeast(0)
    val candidateMonthDate = startLocal.date.plus(calendarMonthDelta, DateTimeUnit.MONTH)
    val wholeCalendarMonths = when {
        candidateMonthDate < endLocal.date -> calendarMonthDelta
        candidateMonthDate == endLocal.date && startLocal.time <= endLocal.time -> calendarMonthDelta
        else -> (calendarMonthDelta - 1).coerceAtLeast(0)
    }

    return when {
        wholeCalendarMonths >= 12 -> LastCheckedAge(
            amount = (wholeCalendarMonths / 12).toLong(),
            unit = LastCheckedUnit.YEAR,
        )
        wholeCalendarMonths >= 1 -> LastCheckedAge(
            amount = wholeCalendarMonths.toLong(),
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