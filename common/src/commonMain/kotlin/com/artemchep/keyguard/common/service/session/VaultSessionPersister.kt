package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlin.time.Clock
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach

/**
 * Writes the master key of an unlocked vault while "Keep the vault unlocked"
 * is on, and clears it otherwise. The platform decides when [run] is active.
 */
class VaultSessionPersister(
    private val getVaultSession: GetVaultSession,
    private val getVaultPersist: GetVaultPersist,
    private val keyReadWriteRepository: KeyReadWriteRepository,
) {
    suspend fun run() {
        combine(
            getVaultSession(),
            getVaultPersist(),
        ) { session, persist ->
            val persistedMasterKey = if (persist && session is MasterSession.Key) {
                session.masterKey
            } else {
                null
            }
            persistedMasterKey
        }
            .onEach { masterKey ->
                val persistedSession = if (masterKey != null) {
                    PersistedSession(
                        masterKey = masterKey,
                        createdAt = Clock.System.now(),
                        persistedAt = Clock.System.now(),
                    )
                } else {
                    null
                }
                keyReadWriteRepository.put(persistedSession)
                    .attempt()
                    .bind()
            }
            .collect()
    }
}
