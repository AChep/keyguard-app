package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRow
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeySyncer
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentSecret
import com.artemchep.keyguard.common.service.gpgagent.hasPrivateKey
import com.artemchep.keyguard.common.service.gpgagent.isUsableAgentKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgKeygrip
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetGpgAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

class GpgAgentPublicKeySyncerImpl(
    private val directDI: DirectDI,
    private val getCiphers: GetCiphers,
    private val getGpgAgent: GetGpgAgent,
    private val getGpgAgentFilter: GetGpgAgentFilter,
    private val getGpgAgentDisplayKeyNames: GetGpgAgentDisplayKeyNames,
    private val gpgAgentPublicKeyRepository: GpgAgentPublicKeyRepository,
    private val logRepository: LogRepository,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GpgAgentPublicKeySyncer {
    companion object {
        private const val TAG = "GpgAgentPublicKeySyncer"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        directDI = directDI,
        getCiphers = directDI.instance(),
        getGpgAgent = directDI.instance(),
        getGpgAgentFilter = directDI.instance(),
        getGpgAgentDisplayKeyNames = directDI.instance(),
        gpgAgentPublicKeyRepository = directDI.instance(),
        logRepository = directDI.instance(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun launch(scope: CoroutineScope): Job = scope.launch {
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
                    filterSecrets(
                        secrets = secrets,
                        filter = filter,
                    )
                }.distinctUntilChanged()

                combine(
                    filteredSecretsFlow,
                    getGpgAgentDisplayKeyNames(),
                ) { filteredSecrets, displayKeyNames ->
                    SyncState.Enabled(
                        keys = mapSecretsToGpgKeys(
                            secrets = filteredSecrets,
                            displayKeyNames = displayKeyNames,
                        ),
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(defaultDispatcher)

        syncStateFlow.collectLatest { state ->
            try {
                when (state) {
                    SyncState.Disabled -> gpgAgentPublicKeyRepository.clear()
                        .bind()

                    is SyncState.Enabled -> gpgAgentPublicKeyRepository.replaceAll(state.keys)
                        .bind()
                }
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

    private fun mapSecretsToGpgKeys(
        secrets: List<GpgAgentSecret>,
        displayKeyNames: Boolean,
    ): List<GpgAgentPublicKeyRow> = secrets
        .flatMap { gpgSecret ->
            val name = gpgSecret.cipher.name.takeIf { displayKeyNames }
            val hasPrivateKey = gpgSecret.hasPrivateKey
            gpgSecret.metadata.keys
                .filter { it.isUsableAgentKey }
                .map { key ->
                    GpgAgentPublicKeyRow(
                        keygrip = key.keygrip.normalizeGpgKeygrip(),
                        fingerprint = key.fingerprint.ifBlank { gpgSecret.fingerprint.orEmpty() },
                        algorithm = key.algorithm,
                        canSign = hasPrivateKey && key.canSign,
                        canDecrypt = hasPrivateKey && key.canDecrypt,
                        publicKeyArmored = gpgSecret.publicKeyArmored,
                        name = name,
                    )
                }
        }

    private suspend fun filterSecrets(
        secrets: List<GpgAgentSecret>,
        filter: GpgAgentFilter,
    ): List<GpgAgentSecret> {
        if (!filter.isActive) {
            return secrets
        }

        val predicate = filter
            .toDFilter()
            .prepare(
                directDI = directDI,
                ciphers = secrets.map { it.cipher },
            )
        return secrets.filter { predicate(it.cipher) }
    }

    private sealed interface SyncState {
        data object Disabled : SyncState

        data class Enabled(
            val keys: List<GpgAgentPublicKeyRow>,
        ) : SyncState
    }
}
