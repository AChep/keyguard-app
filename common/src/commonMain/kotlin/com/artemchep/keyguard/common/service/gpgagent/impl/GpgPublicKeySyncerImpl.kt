package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolverUnsupported
import com.artemchep.keyguard.common.service.crypto.toGpgRevocationKeyCandidates
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgCertificationAuthorityEntry
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyEntry
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeySyncer
import com.artemchep.keyguard.common.service.gpgagent.isGpgAgentSecretType
import com.artemchep.keyguard.common.service.gpgagent.resolveAuthorizationOrClear
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.service.gpgagent.toGpgCertificationAuthorityEntries
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
    private val gpgKeyMetadataResolver: GpgKeyMetadataResolver = GpgKeyMetadataResolverUnsupported,
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
        gpgKeyMetadataResolver = directDI.instance(),
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

                combine(
                    catalogInputFlow(),
                    getGpgAgentDisplayKeyNames(),
                ) { catalogInput, displayKeyNames ->
                    SyncState.Enabled(
                        entries = mapSecretsToEntries(
                            secrets = catalogInput.filteredSecrets,
                            displayKeyNames = displayKeyNames,
                        ),
                        certificationAuthorities = catalogInput.certificationAuthorities,
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(defaultDispatcher)
        return syncStateFlow
    }

    private fun catalogInputFlow(): Flow<CatalogInput> = combine(
        getCiphers(),
        getGpgAgentFilter().map { it.normalize() },
    ) { ciphers, filter ->
        createCatalogInput(ciphers, filter)
    }.distinctUntilChanged()

    private suspend fun createCatalogInput(
        ciphers: List<DSecret>,
        filter: GpgAgentFilter,
    ): CatalogInput {
        val candidateRevocationKeys = ciphers.toGpgRevocationKeyCandidates()
        // One filter pass admits ciphers for both the catalog and the trust
        // authorities, so the two surfaces provably see the same cipher set.
        // Both surfaces only accept live GPG key ciphers, so the filter never
        // needs to see the rest of the vault.
        val filteredCiphers = filter.filterCiphers(
            directDI = directDI,
            ciphers = ciphers.filter { cipher ->
                cipher.isGpgAgentSecretType() && !cipher.deleted
            },
        )
        val filteredSecrets = mutableListOf<GpgAgentSecret>()
        val certificationAuthorities = mutableListOf<GpgCertificationAuthorityEntry>()
        filteredCiphers.forEach { cipher ->
            val secret = cipher.toGpgAgentSecretOrNull()
            if (secret != null) {
                val resolved = secret.resolveAuthorizationOrClear(
                    resolver = gpgKeyMetadataResolver,
                    candidateRevocationKeys = candidateRevocationKeys,
                    logRepository = logRepository,
                    tag = TAG,
                )
                filteredSecrets += resolved
                certificationAuthorities += resolved.toGpgCertificationAuthorityEntries()
            } else {
                certificationAuthorities += cipher.toGpgCertificationAuthorityEntries(
                    resolver = gpgKeyMetadataResolver,
                    candidateRevocationKeys = candidateRevocationKeys,
                    logRepository = logRepository,
                    tag = TAG,
                )
            }
        }
        logRepository.postDebug(TAG) {
            "catalog_input ciphers=${ciphers.size} filtered=${filteredCiphers.size} " +
                    "gpg_items=${filteredSecrets.size} filter_active=${filter.isActive} " +
                    "certification_authorities=${certificationAuthorities.size}"
        }
        return CatalogInput(
            filteredSecrets = filteredSecrets,
            certificationAuthorities = certificationAuthorities,
        )
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
                gpgPublicKeyRepository
                    .replaceSnapshot(
                        publicKeys = state.entries,
                        certificationAuthorities = state.certificationAuthorities,
                    )
                    .bind()
                logRepository.postDebug(TAG) {
                    "catalog_synced ciphers=${state.entries.size} " +
                            "key_info=${state.entries.sumOf { it.keyInfo.size }} " +
                            "certification_authorities=${state.certificationAuthorities.size}"
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
            val certificationAuthorities: List<GpgCertificationAuthorityEntry>,
        ) : SyncState
    }

    private data class CatalogInput(
        val filteredSecrets: List<GpgAgentSecret>,
        val certificationAuthorities: List<GpgCertificationAuthorityEntry>,
    )
}
