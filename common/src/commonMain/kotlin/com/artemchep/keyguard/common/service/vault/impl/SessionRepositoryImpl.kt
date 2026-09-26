package com.artemchep.keyguard.common.service.vault.impl

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * @author Artem Chepurnyi
 */
class SessionRepositoryImpl(
) : SessionReadWriteRepository {
    private val lock = SynchronizedObject()
    private val inMemoryStore = MutableStateFlow<MasterSession?>(null)

    override fun put(key: MasterSession): Unit = synchronized(lock) {
        val previous = inMemoryStore.value as? MasterSession.Key
        val next = key as? MasterSession.Key
        val retired = previous?.session?.takeUnless { it === next?.session }
        retired?.retire()
        inMemoryStore.value = key
        // Cancel without joining: locking may have been initiated by this session's work.
        retired?.close()
    }

    override fun get(): Flow<MasterSession?> = inMemoryStore
}
