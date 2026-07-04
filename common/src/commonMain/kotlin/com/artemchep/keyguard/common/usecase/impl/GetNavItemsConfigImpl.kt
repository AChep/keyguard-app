package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetNavItemsConfig
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.navigation.applyHomeNavigationAvailability
import com.artemchep.keyguard.feature.home.navigation.normalizeHomeNavigationConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
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
            initialValue = NavItemsConfigDefaults.defaultConfig(),
        )

    constructor(
        getAccounts: GetAccounts,
        getProfiles: GetProfiles,
        settingsReadRepository: SettingsReadRepository,
        settingsReadWriteRepository: SettingsReadWriteRepository,
        windowCoroutineScope: WindowCoroutineScope,
    ) : this(
        getAccounts = getAccounts,
        getProfiles = getProfiles,
        getPersistedConfig = settingsReadRepository::getNavItemsConfig,
        getCachedConfig = settingsReadRepository::getCacheNavItemsConfig,
        putCachedConfig = settingsReadWriteRepository::setCacheNavItemsConfig,
        windowCoroutineScope = windowCoroutineScope,
    )

    constructor(directDI: DirectDI) : this(
        getAccounts = directDI.instance(),
        getProfiles = directDI.instance(),
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

    private fun getNavItemsAvailabilityFlow() = getSendAvailabilityFlow()
        .map { sendAvailable ->
            mapOf<NavItemRef, Boolean>(
                NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SENDS) to sendAvailable,
            )
        }

    @OptIn(FlowPreview::class)
    private fun getSendAvailabilityFlow() = combine(
        getAccounts(),
        getProfiles(),
    ) { accounts, profiles ->
        val shownAccountIds = profiles.asSequence()
            .filter { !it.hidden }
            .map { it.accountId }
            .toSet()
        accounts.filter { it.accountId() in shownAccountIds }
    }
        .mapNotNull { accounts ->
            // If the accounts read as empty, then do not change the
            // current value.
            if (accounts.isEmpty()) {
                return@mapNotNull null
            }

            accounts
                .any { account ->
                    account.type.capabilities.supportsSends
                }
        }
        .debounce(1500L) // for slow loading accounts
        .distinctUntilChanged()

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
