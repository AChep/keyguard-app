package com.artemchep.keyguard.common.service.vault.impl

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * @author Artem Chepurnyi
 */
class SessionRepositoryImpl(
) : SessionReadWriteRepository {
    private val inMemoryStore = MutableStateFlow<MasterSession?>(null)

    override fun put(key: MasterSession) {
        inMemoryStore.value = key
    }

    override fun get(): Flow<MasterSession?> = inMemoryStore
}
