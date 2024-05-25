package com.artemchep.keyguard.ui

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

private inline fun daysToHours(days: Long) = days * 24

private inline fun hoursToMinutes(hours: Long) = hours * 60

private inline fun minutesToSeconds(minutes: Long) = minutes * 60
