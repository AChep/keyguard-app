package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesLocalDataSource
import com.artemchep.keyguard.common.usecase.GetBreachesLatestDate
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

class GetBreachesLatestDateImpl(
    private val breachesLocalDataSource: BreachesLocalDataSource,
) : GetBreachesLatestDate {
    override fun invoke(): Flow<LocalDate?> = breachesLocalDataSource
        .getLatestDate()
}
