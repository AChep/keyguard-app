package com.artemchep.keyguard.common.usecase

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

interface DateFormatter {
    fun formatDateTimeMachine(
        instant: Instant,
    ): String

    fun formatDateTime(
        instant: Instant,
    ): String

    fun formatDate(
        instant: Instant,
    ): String

    suspend fun formatDateShort(
        instant: Instant,
    ): String

    suspend fun formatDateShort(
        date: LocalDate,
    ): String

    fun formatDateMedium(
        date: LocalDate,
    ): String

    fun formatTimeShort(
        time: LocalTime,
    ): String
}
