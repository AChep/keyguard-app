package com.artemchep.keyguard.copy

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.text.SimpleDateFormat

class GetAppBuildDateImpl(
    private val formatter: DateFormatter,
) : GetAppBuildDate {
    constructor(directDI: DirectDI) : this(
        formatter = directDI.instance(),
    )

    override fun invoke(): Flow<String> = flow {
        val instant = kotlin.run {
            val dateFormat = SimpleDateFormat("yyyyMMdd")
            val date = dateFormat.parse(BuildKonfig.buildDate)!!
            Instant.fromEpochMilliseconds(date.time)
            Clock.System.now()
        }
        val date = formatter.formatDate(instant)
        emit(date)
    }
}
