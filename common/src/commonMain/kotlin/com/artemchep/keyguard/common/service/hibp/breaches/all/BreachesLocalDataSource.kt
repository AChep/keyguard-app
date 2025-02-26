package com.artemchep.keyguard.common.service.hibp.breaches.all

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.hibp.breaches.all.model.LocalBreachesEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

interface BreachesLocalDataSource {
    fun put(
        entity: LocalBreachesEntity?,
    ): IO<Unit>

    fun get(): Flow<LocalBreachesEntity?>

    fun getLatestDate(): Flow<LocalDate?>

    fun clear(): IO<Unit>
}