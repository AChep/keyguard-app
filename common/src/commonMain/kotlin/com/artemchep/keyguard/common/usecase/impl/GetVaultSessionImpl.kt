package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.core.session.usecase.createSubDi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlin.time.Clock
import org.kodein.di.Copy
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.instance
import org.kodein.di.subDI

class GetVaultSessionImpl(
    private val di: DI,
    private val sessionReadWriteRepository: SessionReadWriteRepository,
    private val keyReadWriteRepository: KeyReadWriteRepository,
) : GetVaultSession {
    companion object {
        private const val TAG = "GetVaultSession"
    }

    constructor(directDI: DirectDI) : this(
        di = directDI.di,
        sessionReadWriteRepository = directDI.instance(),
        keyReadWriteRepository = directDI.instance(),
    )

    private val sharedFlow = sessionReadWriteRepository.get()
        .flatMapLatest { currentSession ->
            if (currentSession != null) {
                flowOf(currentSession)
            } else {
                keyReadWriteRepository.get()
                    .map { persistedSession ->
                        val newSession = if (persistedSession != null) {
                            val masterKey = persistedSession.masterKey
                            val moduleDi =
                                DI.Module("lalala") {
                                    createSubDi(
                                        masterKey = masterKey,
                                    )
                                }
                            val subDi =
                                subDI(di, false, Copy.None) {
                                    import(moduleDi)
                                }
                            MasterSession.Key(
                                masterKey = masterKey,
                                di = subDi,
                                origin = MasterSession.Key.Persisted,
                                createdAt = Clock.System.now(),
                            )
                        } else {
                            MasterSession.Empty()
                        }
                        newSession
                    }
                    .onEach {
                        sessionReadWriteRepository.put(it)
                    }
            }
        }
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(), replay = 1)

    override val valueOrNull: MasterSession?
        get() = sharedFlow.replayCache.firstOrNull()

    override fun invoke(): Flow<MasterSession> = sharedFlow
}
