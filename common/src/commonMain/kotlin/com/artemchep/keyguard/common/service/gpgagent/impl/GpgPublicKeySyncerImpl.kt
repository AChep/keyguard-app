package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyEntry
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeySyncer
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.service.gpgagent.toGpgPublicKeyEntry
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetGpgAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GpgPublicKeySyncerImpl(
    private val directDI: DirectDI,
    private val getCiphers: GetCiphers,
    private val getGpgAgent: GetGpgAgent,
    private val getGpgAgentFilter: GetGpgAgentFilter,
    private val getGpgAgentDisplayKeyNames: GetGpgAgentDisplayKeyNames,
    private val gpgPublicKeyRepository: GpgPublicKeyRepository,
    private val logRepository: LogRepository,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GpgPublicKeySyncer {
    companion object {
        private const val TAG = "GpgPublicKeySyncer"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        directDI = directDI,
        getCiphers = directDI.instance(),
        getGpgAgent = directDI.instance(),
        getGpgAgentFilter = directDI.instance(),
        getGpgAgentDisplayKeyNames = directDI.instance(),
        gpgPublicKeyRepository = directDI.instance(),
        logRepository = directDI.instance(),
    )

    override fun launch(scope: CoroutineScope): Job = scope.launch {
        syncStates().collectLatest { state ->
            try {
                applySyncState(state)
            } catch (e: Exception) {
                e.throwIfFatalOrCancellation()
                logRepository.post(
                    tag = TAG,
                    message = "Failed to sync exposed GPG public key metadata: ${e.message}",
                    level = LogLevel.ERROR,
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun syncStates(): Flow<SyncState> {
        val syncStateFlow = getGpgAgent()
            .distinctUntilChanged()
            .flatMapLatest { gpgAgentEnabled ->
                if (!gpgAgentEnabled) {
                    return@flatMapLatest flowOf(SyncState.Disabled)
                }

                val filteredSecretsFlow = combine(
                    getCiphers(),
                    getGpgAgentFilter()
                        .map { it.normalize() },
                ) { ciphers, filter ->
                    val secrets = ciphers
                        .mapNotNull { it.toGpgAgentSecretOrNull() }
                    val filteredSecrets = filter.filterCiphers(
                        directDI = directDI,
                        items = secrets,
                        cipherOf = { it.cipher },
                    )
                    logRepository.postDebug(TAG) {
                        "catalog_input ciphers=${ciphers.size} gpg_items=${secrets.size} " +
                                "filter_active=${filter.isActive} filtered=${filteredSecrets.size}"
                    }
                    filteredSecrets
                }.distinctUntilChanged()

                combine(
                    filteredSecretsFlow,
                    getGpgAgentDisplayKeyNames(),
                ) { filteredSecrets, displayKeyNames ->
                    SyncState.Enabled(
                        entries = mapSecretsToEntries(
                            secrets = filteredSecrets,
                            displayKeyNames = displayKeyNames,
                        ),
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(defaultDispatcher)
        return syncStateFlow
    }

    private suspend fun applySyncState(state: SyncState) {
        when (state) {
            SyncState.Disabled -> {
                gpgPublicKeyRepository.clear()
                    .bind()
                logRepository.postDebug(TAG) {
                    "catalog_cleared reason=providers_disabled"
                }
            }

            is SyncState.Enabled -> {
                gpgPublicKeyRepository.replaceAll(state.entries)
                    .bind()
                logRepository.postDebug(TAG) {
                    "catalog_synced ciphers=${state.entries.size} " +
                            "key_info=${state.entries.sumOf { it.keyInfo.size }}"
                }
            }
        }
    }

    private fun mapSecretsToEntries(
        secrets: List<GpgAgentSecret>,
        displayKeyNames: Boolean,
    ): List<GpgPublicKeyEntry> {
        val entries = secrets.map { secret ->
            secret.toGpgPublicKeyEntry(
                name = secret.cipher.name.takeIf { displayKeyNames },
            )
        }
        logRepository.postDebug(TAG) {
            "catalog_built source=${secrets.size} entries=${entries.size} " +
                    "key_info=${entries.sumOf { it.keyInfo.size }} " +
                    "missing_public_key=${entries.count { it.publicKeyArmored == null }} " +
                    "missing_fingerprint=${entries.count { it.primaryFingerprint == null }}"
        }
        return entries
    }

    private sealed interface SyncState {
        data object Disabled : SyncState

        data class Enabled(
            val entries: List<GpgPublicKeyEntry>,
        ) : SyncState
    }
}
