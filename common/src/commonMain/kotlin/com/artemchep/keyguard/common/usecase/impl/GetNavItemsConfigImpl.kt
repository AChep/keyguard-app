package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetNavItemsConfig
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.feature.home.navigation.applyHomeNavigationAvailability
import com.artemchep.keyguard.feature.home.navigation.normalizeHomeNavigationConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetNavItemsConfigImpl(
    private val getAccounts: GetAccounts,
    private val getProfiles: GetProfiles,
    private val getCiphers: GetCiphers,
    private val getPersistedConfig: () -> Flow<NavItemsConfig?>,
    private val getCachedConfig: () -> Flow<NavItemsConfig?>,
    private val putCachedConfig: (NavItemsConfig) -> IO<Unit>,
    private val windowCoroutineScope: WindowCoroutineScope,
) : GetNavItemsConfig {
    private val sharedFlow = merge(
        upstreamStatusFlow(),
        localStatusFlow(),
    )
        .runningReduce { previous, current ->
            current.takeIf { it.isUpstream }
                ?: previous.takeIf { it.isUpstream }
                ?: current
        }
        .onEachCacheUpstream()
        .map { it.config }
        .distinctUntilChanged()
        .stateIn(
            scope = windowCoroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = initialConfig(),
        )

    constructor(
        getAccounts: GetAccounts,
        getProfiles: GetProfiles,
        getCiphers: GetCiphers,
        settingsReadRepository: SettingsReadRepository,
        settingsReadWriteRepository: SettingsReadWriteRepository,
        windowCoroutineScope: WindowCoroutineScope,
    ) : this(
        getAccounts = getAccounts,
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        getPersistedConfig = settingsReadRepository::getNavItemsConfig,
        getCachedConfig = settingsReadRepository::getCacheNavItemsConfig,
        putCachedConfig = settingsReadWriteRepository::setCacheNavItemsConfig,
        windowCoroutineScope = windowCoroutineScope,
    )

    constructor(directDI: DirectDI) : this(
        getAccounts = directDI.instance(),
        getProfiles = directDI.instance(),
        getCiphers = directDI.instance(),
        settingsReadRepository = directDI.instance(),
        settingsReadWriteRepository = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    override fun invoke() = sharedFlow

    private fun localStatusFlow() = getCachedConfig()
        .mapNotNull { config ->
            config?.let {
                NavItemsConfigStatus(
                    config = it,
                    isUpstream = false,
                )
            }
        }

    private fun upstreamStatusFlow() = combine(
        getPersistedConfig(),
        getNavItemsAvailabilityFlow(),
    ) { config, availability ->
        val normalizedConfig = normalizeHomeNavigationConfig(config)
        val effectiveConfig = applyHomeNavigationAvailability(
            config = normalizedConfig,
            availability = availability,
        )
        NavItemsConfigStatus(
            config = effectiveConfig,
            isUpstream = true,
        )
    }

    private fun getNavItemsAvailabilityFlow() = combine(
        getSendAvailabilityFlow(),
        getGpgToolsAvailabilityFlow(),
    ) { sendAvailable, gpgToolsAvailable ->
        mapOf<NavItemRef, Boolean>(
            NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SENDS) to sendAvailable,
            NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_GPG_TOOLS) to gpgToolsAvailable,
        )
    }

    private fun getSendAvailabilityFlow() = combine(
        getAccounts(),
        getProfiles(),
    ) { accounts, profiles -> accounts to profiles }
        .mapToAvailable { (accounts, profiles) ->
            // Wait for matching snapshots in either loading order, including partial
            // profile reads. Two empty snapshots resolve an accountless vault.
            val accountIds = accounts.map { it.accountId() }.toSet()
            val profileAccountIds = profiles.map { it.accountId }.toSet()
            if (accountIds != profileAccountIds) {
                return@mapToAvailable null
            }

            val shownAccountIds = profiles.asSequence()
                .filter { !it.hidden }
                .map { it.accountId }
                .toSet()
            accounts.any { account ->
                account.accountId() in shownAccountIds && account.type.capabilities.supportsSends
            }
        }

    private fun getGpgToolsAvailabilityFlow() = filterHiddenProfiles(
        getCiphers = getCiphers,
        getProfiles = getProfiles,
    )
        .mapToAvailable(
            initialWhenUnknown = false,
        ) { ciphers ->
            // An empty snapshot means the ciphers are not loaded yet.
            ciphers
                .takeIf { it.isNotEmpty() }
                ?.any { cipher -> cipher.toGpgAgentSecretOrNull() != null }
        }

    private fun Flow<NavItemsConfigStatus>.onEachCacheUpstream() =
        map { status ->
            if (status.isUpstream) {
                putCachedConfig(status.config)
                    .attempt()
                    .launchIn(windowCoroutineScope)
            }
            status
        }

    private data class NavItemsConfigStatus(
        val config: NavItemsConfig,
        val isUpstream: Boolean,
    )
}

/**
 * Maps each snapshot to an availability flag, where a `null` means that the
 * availability can not be told from that snapshot yet.
 */
@OptIn(FlowPreview::class)
private fun <T> Flow<T>.mapToAvailable(
    initialWhenUnknown: Boolean? = null,
    transform: (T) -> Boolean?,
): Flow<Boolean> = flow {
    var initialized = false
    this@mapToAvailable
        .debounce(1500L)
        .collect { snapshot ->
            val available = transform(snapshot)
            if (available == null) {
                // Some sources need an initial unknown snapshot to establish a
                // baseline, but later unknown snapshots are treated as transient.
                if (!initialized && initialWhenUnknown != null) {
                    initialized = true
                    emit(initialWhenUnknown)
                }
                return@collect
            }

            initialized = true
            emit(available)
        }
}
    .distinctUntilChanged()

private fun initialConfig(): NavItemsConfig = applyHomeNavigationAvailability(
    config = NavItemsConfigDefaults.defaultConfig(),
    availability = mapOf(
        NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SENDS) to false,
        NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_GPG_TOOLS) to false,
    ),
)
