package com.artemchep.keyguard.common.service.sshagent.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRow
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeySyncer
import com.artemchep.keyguard.common.service.sshagent.createSshAgentPublicKeyRow
import com.artemchep.keyguard.common.service.sshagent.isEligibleForSshAgent
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
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
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

class SshAgentPublicKeySyncerImpl(
    private val directDI: DirectDI,
    private val getCiphers: GetCiphers,
    private val getSshAgent: GetSshAgent,
    private val getSshAgentFilter: GetSshAgentFilter,
    private val getSshAgentDisplayKeyNames: GetSshAgentDisplayKeyNames,
    private val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val logRepository: LogRepository,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SshAgentPublicKeySyncer {
    companion object {
        private const val TAG = "SshAgentPublicKeySyncer"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        directDI = directDI,
        getCiphers = directDI.instance(),
        getSshAgent = directDI.instance(),
        getSshAgentFilter = directDI.instance(),
        getSshAgentDisplayKeyNames = directDI.instance(),
        sshAgentPublicKeyRepository = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
        logRepository = directDI.instance(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun launch(scope: CoroutineScope): Job = scope.launch {
        val syncStateFlow = getSshAgent()
            .distinctUntilChanged()
            .flatMapLatest { sshAgentEnabled ->
                if (!sshAgentEnabled) {
                    return@flatMapLatest flowOf(SyncState.Disabled)
                }

                val filteredCiphersFlow = combine(
                    getCiphers(),
                    getSshAgentFilter()
                        .map { it.normalize() },
                ) { ciphers, filter ->
                    val eligibleCiphers = ciphers
                        .filter { it.isEligibleForSshAgent() }
                    filterCiphers(
                        ciphers = eligibleCiphers,
                        filter = filter,
                    )
                }
                    .distinctUntilChanged()
                combine(
                    filteredCiphersFlow,
                    getSshAgentDisplayKeyNames(),
                ) { filteredCiphers, displayKeyNames ->
                    val keys = mapCiphersToSshKeys(
                        ciphers = filteredCiphers,
                        displayKeyNames = displayKeyNames,
                    )
                    SyncState.Enabled(
                        keys = keys,
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(defaultDispatcher)

        syncStateFlow.collectLatest { state ->
            try {
                when (state) {
                    SyncState.Disabled -> {
                        sshAgentPublicKeyRepository.clear()
                            .bind()
                    }

                    is SyncState.Enabled -> sync(state)
                }
            } catch (e: Exception) {
                e.throwIfFatalOrCancellation()
                logRepository.post(
                    tag = TAG,
                    message = "Failed to sync exposed SSH public keys: ${e.message}",
                    level = LogLevel.ERROR,
                )
            }
        }
    }

    private suspend fun sync(
        state: SyncState.Enabled,
    ) {
        val keys = state.keys
        sshAgentPublicKeyRepository.replaceAll(keys)
            .bind()
    }

    private suspend fun mapCiphersToSshKeys(
        ciphers: List<DSecret>,
        displayKeyNames: Boolean,
    ): List<SshAgentPublicKeyRow> = ciphers
        .mapNotNull { cipher ->
            val sshKey = cipher.sshKey
                ?: return@mapNotNull null
            val publicKey = sshKey.publicKey
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            createSshAgentPublicKeyRow(
                publicKey = publicKey,
                fingerprint = sshKey.fingerprint.orEmpty(),
                name = cipher.name
                    .takeIf { displayKeyNames },
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
        }

    private suspend fun filterCiphers(
        ciphers: List<DSecret>,
        filter: SshAgentFilter,
    ): List<DSecret> {
        if (!filter.isActive) {
            return ciphers
        }

        val predicate = filter
            .toDFilter()
            .prepare(
                directDI = directDI,
                ciphers = ciphers,
            )
        return ciphers.filter(predicate)
    }

    private sealed interface SyncState {
        data object Disabled : SyncState

        data class Enabled(
            val keys: List<SshAgentPublicKeyRow>,
        ) : SyncState
    }
}
