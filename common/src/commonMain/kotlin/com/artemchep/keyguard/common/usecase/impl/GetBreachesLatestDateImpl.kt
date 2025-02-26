package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesLocalDataSource
import com.artemchep.keyguard.common.usecase.GetBreachesLatestDate
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetBreachesLatestDateImpl(
    private val breachesLocalDataSource: BreachesLocalDataSource,
) : GetBreachesLatestDate {
    constructor(directDI: DirectDI) : this(
        breachesLocalDataSource = directDI.instance(),
    )

    override fun invoke(): Flow<LocalDate?> = breachesLocalDataSource
        .getLatestDate()
}
