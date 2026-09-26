package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant

class GetAppBuildDateImpl(
    private val formatter: DateFormatter,
    private val buildDate: String = BuildKonfig.buildDate,
) : GetAppBuildDate {
    override fun invoke(): Flow<String> = flow {
        // Build metadata is an ISO calendar date. Local noon keeps the
        // same day when the formatter renders it in the device time zone.
        val instant = LocalDate.parse(buildDate, LocalDate.Formats.ISO_BASIC)
            .atTime(12, 0)
            .toInstant(TimeZone.currentSystemDefault())
        emit(formatter.formatDate(instant))
    }
}
