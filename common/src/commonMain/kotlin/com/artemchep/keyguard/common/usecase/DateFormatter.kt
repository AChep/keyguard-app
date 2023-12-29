package com.artemchep.keyguard.common.usecase

import kotlinx.datetime.Instant

interface DateFormatter {
    fun formatDateTime(
        instant: Instant,
    ): String

    fun formatDate(
        instant: Instant,
    ): String

    fun formatDateShort(
        instant: Instant,
    ): String
}
