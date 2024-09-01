package com.artemchep.keyguard.ui

import com.artemchep.keyguard.common.model.DurationSimple
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration

suspend fun Duration.format(context: LeContext): String {
    if (this == Duration.INFINITE) {
        return textResource(Res.string.expiration_date_never, context)
    }

    return flow<String> {
        val days = inWholeDays
        if (days > 0) {
            val daysFormatted = textResource(
                res = Res.plurals.days_plural,
                context = context,
                quantity = days.toInt(),
                days.toString(),
            )
            emit(daysFormatted)
        }
        val hours = inWholeHours - daysToHours(days)
        if (hours > 0) {
            val hoursFormatted = textResource(
                res = Res.plurals.hours_plural,
                context = context,
                quantity = hours.toInt(),
                hours.toString(),
            )
            emit(hoursFormatted)
        }
        val minutes = inWholeMinutes - hoursToMinutes(daysToHours(days) + hours)
        if (minutes > 0) {
            val minutesFormatted = textResource(
                res = Res.plurals.minutes_plural,
                context = context,
                quantity = minutes.toInt(),
                minutes.toString(),
            )
            emit(minutesFormatted)
        }
        val seconds =
            inWholeSeconds - minutesToSeconds(hoursToMinutes(daysToHours(days) + hours) + minutes)
        if (seconds > 0) {
            val secondsFormatted = textResource(
                res = Res.plurals.seconds_plural,
                context = context,
                quantity = seconds.toInt(),
                seconds.toString(),
            )
            emit(secondsFormatted)
        }
    }
        .toList()
        .joinToString(separator = " ")
        .takeIf { it.isNotEmpty() }
        ?: toString()
}

suspend fun DurationSimple.format(context: LeContext): String {
    return flow<String> {
        if (years > 0) {
            val yearsFormatted = textResource(
                res = Res.plurals.years_plural,
                context = context,
                quantity = years,
                years.toString(),
            )
            emit(yearsFormatted)
        }
        if (months > 0) {
            val monthsFormatted = textResource(
                res = Res.plurals.months_plural,
                context = context,
                quantity = months,
                months.toString(),
            )
            emit(monthsFormatted)
        }
        if (weeks > 0) {
            val weeksFormatted = textResource(
                res = Res.plurals.weeks_plural,
                context = context,
                quantity = weeks,
                weeks.toString(),
            )
            emit(weeksFormatted)
        }
        if (days > 0) {
            val daysFormatted = textResource(
                res = Res.plurals.days_plural,
                context = context,
                quantity = days,
                days.toString(),
            )
            emit(daysFormatted)
        }
    }
        .toList()
        .joinToString(separator = " ")
        .takeIf { it.isNotEmpty() }
        ?: toString()
}

private inline fun daysToHours(days: Long) = days * 24

private inline fun hoursToMinutes(hours: Long) = hours * 60

private inline fun minutesToSeconds(minutes: Long) = minutes * 60
