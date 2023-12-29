package com.artemchep.keyguard.common.service.hibp.breaches.all

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.hibp.breaches.all.model.LocalBreachesEntity
import kotlinx.coroutines.flow.Flow

interface BreachesLocalDataSource {
    fun put(
        entity: LocalBreachesEntity?,
    ): IO<Unit>

    fun get(): Flow<LocalBreachesEntity?>

    fun clear(): IO<Unit>
}