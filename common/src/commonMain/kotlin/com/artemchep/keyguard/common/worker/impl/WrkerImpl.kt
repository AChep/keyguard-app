package com.artemchep.keyguard.common.worker.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.session.AccountSessionAccess
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.usecase.CleanUpAttachment
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class WrkerImpl(
    private val logRepository: LogRepository,
    private val getVaultSession: GetVaultSession,
    private val accountSessionAccess: AccountSessionAccess,
    private val putVaultSession: PutVaultSession,
    private val getVaultPersist: GetVaultPersist,
    private val keyReadWriteRepository: KeyReadWriteRepository,
    private val clearVaultSession: ClearVaultSession,
    private val downloadRepository: DownloadRepository,
    private val cleanUpAttachment: CleanUpAttachment,
) : Wrker {
    override fun start(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ) {
        flow
            .onState {
                getVaultSession()
                    .map { session ->
                        val key = session as? MasterSession.Key
                        key?.let(accountSessionAccess::invoke)
                    }
                    .collectLatest { getAccounts ->
                        if (getAccounts != null) {
                            coroutineScope {
                                Favicon.launch(this, getAccounts)
                            }
                        }
                    }
            }
        flow
            .onState {
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
        flow
            .onState {
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
}
