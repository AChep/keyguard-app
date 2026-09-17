package com.artemchep.keyguard.copy

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import java.text.SimpleDateFormat
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class GetAppBuildDateImpl(
    private val formatter: DateFormatter,
) : GetAppBuildDate {

    override fun invoke(): Flow<String> = flow {
        val instant = kotlin.run {
            val dateFormat = SimpleDateFormat("yyyyMMdd")
            val date = dateFormat.parse(BuildKonfig.buildDate)!!
            Instant.fromEpochMilliseconds(date.time)
        }
        val date = formatter.formatDate(instant)
        emit(date)
    }
}
