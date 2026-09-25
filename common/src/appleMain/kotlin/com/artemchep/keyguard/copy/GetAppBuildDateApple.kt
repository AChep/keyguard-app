package com.artemchep.keyguard.copy

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate

class GetAppBuildDateApple(
    private val formatter: DateFormatter,
    private val buildDate: String = BuildKonfig.buildDate,
) : GetAppBuildDate {
    override fun invoke(): Flow<String> = flow {
        // Build metadata is a calendar date, not a UTC instant. Keep it as a date
        // so displaying it west of UTC cannot move it to the previous day.
        val date = LocalDate(
            year = buildDate.substring(0, 4).toInt(),
            month = buildDate.substring(4, 6).toInt(),
            day = buildDate.substring(6, 8).toInt(),
        )
        emit(formatter.formatDateMedium(date))
    }
}
