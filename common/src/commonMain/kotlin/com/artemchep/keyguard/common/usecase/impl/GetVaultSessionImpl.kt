package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.service.vault.VaultSessionFactory
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

class GetVaultSessionImpl(
    private val sessionFactory: VaultSessionFactory,
    private val sessionReadWriteRepository: SessionReadWriteRepository,
    private val keyReadWriteRepository: KeyReadWriteRepository,
) : GetVaultSession {
    companion object {
        private const val TAG = "GetVaultSession"
    }

    private val sharedFlow = sessionReadWriteRepository.get()
        .flatMapLatest { currentSession ->
            if (currentSession != null) {
                flowOf(currentSession)
            } else {
                keyReadWriteRepository.get()
                    .map { persistedSession ->
                        val newSession = if (persistedSession != null) {
                            val masterKey = persistedSession.masterKey
                            val session = sessionFactory.create(masterKey)
                            MasterSession.Key(
                                masterKey = masterKey,
                                session = session,
                                origin = MasterSession.Key.Persisted,
                                createdAt = Clock.System.now(),
                            )
                        } else {
                            MasterSession.Empty()
                        }
                        newSession
                    }
                    .onEach {
                        var published = false
                        try {
                            withContext(Dispatchers.Main.immediate) {
                                sessionReadWriteRepository.put(it)
                                published = true
                            }
                        } finally {
                            if (!published) (it as? MasterSession.Key)?.session?.close()
                        }
                    }
            }
        }
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(), replay = 1)

    override val valueOrNull: MasterSession?
        get() = sharedFlow.replayCache.firstOrNull()

    override fun invoke(): Flow<MasterSession> = sharedFlow
}
