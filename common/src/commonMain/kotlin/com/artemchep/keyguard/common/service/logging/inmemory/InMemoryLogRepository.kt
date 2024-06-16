package com.artemchep.keyguard.common.service.logging.inmemory

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.Log
import com.artemchep.keyguard.common.service.logging.LogRepositoryChild
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow

interface InMemoryLogRepository : LogRepositoryChild {
    val isEnabled: Boolean

    fun setEnabled(enabled: Boolean): IO<Unit>

    fun getEnabled(): Flow<Boolean>

    fun get(): Flow<ImmutableList<Log>>
}
