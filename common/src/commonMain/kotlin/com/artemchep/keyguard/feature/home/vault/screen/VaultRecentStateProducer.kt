package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import arrow.core.partially1
import com.artemchep.keyguard.common.model.CipherOpenedHistoryMode
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CipherToolbox
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.ui.tabs.CallsTabs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun vaultRecentScreenState(
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
) = with(localDI().direct) {
    vaultRecentScreenState(
        directDI = this,
        highlightBackgroundColor = highlightBackgroundColor,
        highlightContentColor = highlightContentColor,
        clearVaultSession = instance(),
        getAccounts = instance(),
        getCanWrite = instance(),
        getCiphers = instance(),
        getProfiles = instance(),
        getFolders = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        getTotpCode = instance(),
        getConcealFields = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
        getPasswordStrength = instance(),
        getCipherOpenedHistory = instance(),
        toolbox = instance(),
        queueSyncAll = instance(),
        syncSupervisor = instance(),
        dateFormatter = instance(),
        clipboardService = instance(),
    )
}

@Composable
fun vaultRecentScreenState(
    directDI: DirectDI,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    getAccounts: GetAccounts,
    getCanWrite: GetCanWrite,
    getCiphers: GetCiphers,
    getProfiles: GetProfiles,
    getFolders: GetFolders,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    getTotpCode: GetTotpCode,
    getConcealFields: GetConcealFields,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    getPasswordStrength: GetPasswordStrength,
    getCipherOpenedHistory: GetCipherOpenedHistory,
    clearVaultSession: ClearVaultSession,
    toolbox: CipherToolbox,
    queueSyncAll: QueueSyncAll,
    syncSupervisor: SupervisorRead,
    dateFormatter: DateFormatter,
    clipboardService: ClipboardService,
): Loadable<VaultRecentState> = produceScreenState(
    key = "vault_recent",
    initial = Loadable.Loading,
    args = arrayOf(
        getAccounts,
        getCiphers,
        getTotpCode,
        dateFormatter,
        clipboardService,
    ),
) {
    val storage = kotlin.run {
        val disk = loadDiskHandle("vault.recent")
        PersistedStorage.InDisk(disk)
    }

    val tabSink = mutablePersistedFlow(
        key = "tab",
        storage = storage,
    ) {
        CallsTabs.default.name
    }
    val tabFlow = tabSink
        .map {
            kotlin.runCatching {
                CallsTabs.valueOf(it)
            }.getOrNull() ?: CallsTabs.default
        }
        .stateIn(screenScope)

    val copy = copy(clipboardService)

    val cipherSink = EventFlow<DSecret>()

    val recentState = MutableStateFlow(
        VaultItem2.Item.LocalState(
            openedState = VaultItem2.Item.OpenedState(false),
            selectableItemState = SelectableItemState(
                selected = false,
                selecting = false,
                can = true,
                onClick = null,
                onLongClick = null,
            ),
        ),
    )

    data class ConfigMapper(
        val concealFields: Boolean,
        val appIcons: Boolean,
        val websiteIcons: Boolean,
    )

    val ciphersRawFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        filter = null,
    )
    val configFlow = combine(
        getConcealFields(),
        getAppIcons(),
        getWebsiteIcons(),
    ) { concealFields, appIcons, websiteIcons ->
        ConfigMapper(
            concealFields = concealFields,
            appIcons = appIcons,
            websiteIcons = websiteIcons,
        )
    }.distinctUntilChanged()
    val organizationsByIdFlow = getOrganizations()
        .map { organizations ->
            organizations
                .associateBy { it.id }
        }
    val recentFLow = tabFlow
        .flatMapLatest { tab ->
            val mode = when (tab) {
                CallsTabs.RECENTS -> CipherOpenedHistoryMode.Recent
                CallsTabs.FAVORITES -> CipherOpenedHistoryMode.Popular
            }
            getCipherOpenedHistory(mode)
        }
        .map { items ->
            items
                .asSequence()
                .map { it.cipherId }
                .toSet()
        }
        .combine(ciphersRawFlow) { recents, ciphers ->
            recents
                .mapNotNull { recentId ->
                    ciphers
                        .firstOrNull { it.id == recentId }
                }
        }

    val recent = combine(
        recentFLow,
        organizationsByIdFlow,
        configFlow,
    ) { secrets, organizationsById, cfg -> Triple(secrets, organizationsById, cfg) }
        .map { (secrets, organizationsById, cfg) ->
            secrets
                .map { secret ->
                    secret.toVaultListItem(
                        copy = copy,
                        translator = this,
                        getTotpCode = getTotpCode,
                        concealFields = cfg.concealFields,
                        appIcons = cfg.appIcons,
                        websiteIcons = cfg.websiteIcons,
                        organizationsById = organizationsById,
                        localStateFlow = recentState,
                        onClick = { actions ->
                            VaultItem2.Item.Action.Go(
                                onClick = cipherSink::emit.partially1(secret),
                            )
                        },
                        onClickAttachment = {
                            null
                        },
                        onClickPasskey = {
                            null
                        },
                    )
                }
        }

    val defaultState = VaultRecentState(
        recent = recent
            .stateIn(screenScope),
        selectedTab = tabFlow,
        onSelectTab = { tab ->
            tabSink.value = tab.name
        },
        sideEffects = VaultRecentState.SideEffects(
            selectCipherFlow = cipherSink,
        ),
    )
    flowOf(Loadable.Ok(defaultState))
}
