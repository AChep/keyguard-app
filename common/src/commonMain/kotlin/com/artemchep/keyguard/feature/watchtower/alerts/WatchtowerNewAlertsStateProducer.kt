package com.artemchep.keyguard.feature.watchtower.alerts

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.CipherId
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlert
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.firstOrNull
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DismissNotificationsByChannel
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetWatchtowerAlerts
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsRead
import com.artemchep.keyguard.common.usecase.MarkWatchtowerAlertsAsRead
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.forEachWithDecorUniqueSectionsOnly
import com.artemchep.keyguard.feature.generator.history.GeneratorHistoryItem
import com.artemchep.keyguard.feature.generator.history.mapLatestScoped
import com.artemchep.keyguard.feature.generator.wordlist.list.WordlistListState
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRoute
import com.artemchep.keyguard.feature.home.vault.screen.toVaultListItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.feature.watchtower.DISMISS_NOTIFICATIONS_DELAY_MS
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentHashSet
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import org.koin.compose.currentKoinScope

private data class AhAh(
    val cipher: DSecret,
    val alert: DWatchtowerAlert,
)

private data class ConfigMapper(
    val concealFields: Boolean,
    val appIcons: Boolean,
    val websiteIcons: Boolean,
)

@Composable
fun produceGeneratorHistoryState(
    args: WatchtowerAlertsRoute.Args,
) = with(currentKoinScope()) {
    produceGeneratorHistoryState(
        filterContext = get(),
        args = args,
        markAllWatchtowerAlertAsRead = get(),
        markWatchtowerAlertsAsRead = get(),
        getProfiles = get(),
        getOrganizations = get(),
        getCiphers = get(),
        getWatchtowerAlerts = get(),
        getTotpCode = get(),
        getConcealFields = get(),
        getAppIcons = get(),
        getWebsiteIcons = get(),
        dateFormatter = get(),
        clipboardService = get(),
        dismissNotificationsByChannel = get(),
    )
}

@Composable
fun produceGeneratorHistoryState(
    filterContext: CipherFilterContext,
    args: WatchtowerAlertsRoute.Args,
    markAllWatchtowerAlertAsRead: MarkAllWatchtowerAlertAsRead,
    markWatchtowerAlertsAsRead: MarkWatchtowerAlertsAsRead,
    getProfiles: GetProfiles,
    getOrganizations: GetOrganizations,
    getCiphers: GetCiphers,
    getWatchtowerAlerts: GetWatchtowerAlerts,
    getTotpCode: GetTotpCode,
    getConcealFields: GetConcealFields,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    dateFormatter: DateFormatter,
    clipboardService: ClipboardService,
    dismissNotificationsByChannel: DismissNotificationsByChannel,
): Loadable<WatchtowerNewAlertsState> = produceScreenState(
    initial = Loadable.Loading,
    key = "watchtower_new_alerts",
    args = arrayOf(
        dateFormatter,
        clipboardService,
    ),
) {
    watchtowerNewAlertsStateProducer(
        filterContext = filterContext,
        args = args,
        markAllWatchtowerAlertAsRead = markAllWatchtowerAlertAsRead,
        markWatchtowerAlertsAsRead = markWatchtowerAlertsAsRead,
        getProfiles = getProfiles,
        getOrganizations = getOrganizations,
        getCiphers = getCiphers,
        getWatchtowerAlerts = getWatchtowerAlerts,
        getTotpCode = getTotpCode,
        getConcealFields = getConcealFields,
        getAppIcons = getAppIcons,
        getWebsiteIcons = getWebsiteIcons,
        dateFormatter = dateFormatter,
        clipboardService = clipboardService,
        dismissNotificationsByChannel = dismissNotificationsByChannel,
    )
}

suspend fun RememberStateFlowScope.watchtowerNewAlertsStateProducer(
    filterContext: CipherFilterContext,
    args: WatchtowerAlertsRoute.Args,
    markAllWatchtowerAlertAsRead: MarkAllWatchtowerAlertAsRead,
    markWatchtowerAlertsAsRead: MarkWatchtowerAlertsAsRead,
    getProfiles: GetProfiles,
    getOrganizations: GetOrganizations,
    getCiphers: GetCiphers,
    getWatchtowerAlerts: GetWatchtowerAlerts,
    getTotpCode: GetTotpCode,
    getConcealFields: GetConcealFields,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    dateFormatter: DateFormatter,
    clipboardService: ClipboardService,
    dismissNotificationsByChannel: DismissNotificationsByChannel,
): Flow<Loadable<WatchtowerNewAlertsState>> {
    // Dismiss all the notifications related to
    // the watchtower. You're on the watchtower
    // screen, so you must have seen all the alerts.
    isStartedFlow
        .filter { it }
        .onEach {
            delay(DISMISS_NOTIFICATIONS_DELAY_MS)
            dismissNotificationsByChannel(DNotificationChannel.WATCHTOWER)
                .bind()
        }
        .launchIn(screenScope)

    val copy = copier()

    fun navigatePopAll() {
        val intent = NavigationIntent.PopById(
            WatchtowerAlertsRoute.ROUTER_NAME,
            exclusive = false,
        )
        navigate(intent)
    }

    fun onClick(
        item: AhAh,
    ) {
        val route = VaultViewRoute(
            itemId = item.cipher.id,
            accountId = item.cipher.accountId,
            tag = item.alert.alertId,
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(WatchtowerAlertsRoute.ROUTER_NAME),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        navigate(intent)
    }

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

    val ciphersRawFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        filter = args.filter,
    )
        .map { ciphers ->
            if (args.filter != null) {
                val predicate = args.filter.prepare(filterContext, ciphers)
                ciphers
                    .filter { predicate(it) }
            } else {
                ciphers
            }
        }
    val ciphersFlow = ciphersRawFlow
        .map { secrets ->
            secrets
                .filter { secret -> !secret.deleted }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    fun onMarkAllRead() {
        val io = if (args.filter == null) {
            markAllWatchtowerAlertAsRead()
        } else {
            ioEffect {
                // Reuse the exact account/tag/custom-filter scope of this list.
                val ids = ciphersFlow.first()
                    .mapTo(mutableSetOf()) { CipherId(it.id) }
                markWatchtowerAlertsAsRead(ids)
                    .bind()
            }
        }
        io
            .effectTap {
                navigatePopAll()
            }
            .launchIn(appScope)
    }

    val itemSink = mutablePersistedFlow("alert") { "" }
    val selectionHandle = selectionHandle("selection")
    // Automatically remove selection from ciphers
    // that do not exist anymore.
    ciphersFlow
        .onEach { ciphers ->
            val selectedItemIds = selectionHandle.idsFlow.value
            val filteredSelectedItemIds = selectedItemIds
                .filter { itemId ->
                    ciphers.any { it.id == itemId }
                }
                .toSet()
            if (filteredSelectedItemIds.size < selectedItemIds.size) {
                selectionHandle.setSelection(filteredSelectedItemIds)
            }
        }
        .launchIn(this)

    // TODO: It should be saved in the persistent flow,
    //  so it survives the view model recreation.
    val alertsUnreadMemoFlow = getWatchtowerAlerts()
        .map { alerts ->
            alerts
                .asSequence()
                .filter { !it.read }
                .map { it.alertId }
                .toPersistentHashSet()
        }
        .runningReduce { accumulator, value ->
            accumulator.addAll(value)
        }
        .shareInScreenScope(SharingStarted.Lazily)
    val alertsFlow = getWatchtowerAlerts()
        .combine(alertsUnreadMemoFlow) { alerts, memo ->
            alerts
                .filter { it.alertId in memo }
        }

    data class Data(
        val ciphers: List<DSecret>,
        val alerts: List<DWatchtowerAlert>,
        val organizationsById: Map<String, DOrganization>,
        val config: ConfigMapper,
    )

    val itemsRawFlow = combine(
        ciphersFlow,
        alertsFlow,
        organizationsByIdFlow,
        configFlow,
    ) { ciphers, alerts, organizationsById, config ->
        Data(
            ciphers = ciphers,
            alerts = alerts,
            organizationsById = organizationsById,
            config = config,
        )
    }
        .mapLatestScoped { data ->
            data.alerts
                .mapNotNull { alert ->
                    val cipher = data.ciphers.firstOrNull(alert.cipherId)
                        ?: return@mapNotNull null
                    val el = AhAh(
                        cipher = cipher,
                        alert = alert,
                    )

                    val selectableFlow = selectionHandle
                        .idsFlow
                        .map { ids ->
                            SelectableItemStateRaw(
                                selecting = ids.isNotEmpty(),
                                selected = alert.alertId in ids,
                            )
                        }
                        .distinctUntilChanged()
                        .map { raw ->
                            val toggle = selectionHandle::toggleSelection
                                .partially1(alert.alertId)
                            val onClick = toggle.takeIf { raw.selecting }
                            val onLongClick = toggle
                                .takeIf { !raw.selecting && raw.canSelect }
                            SelectableItemState(
                                selecting = raw.selecting,
                                selected = raw.selected,
                                can = raw.canSelect,
                                onClick = onClick,
                                onLongClick = onLongClick,
                            )
                        }
                    val openedStateFlow = itemSink
                        .map {
                            val isOpened = it == alert.alertId
                            VaultItem2.Item.OpenedState(isOpened)
                        }
                    val sharing = SharingStarted.WhileSubscribed(1000L)
                    val localStateFlow = combine(
                        selectableFlow,
                        openedStateFlow,
                    ) { selectableState, openedState ->
                        VaultItem2.Item.LocalState(
                            openedState,
                            selectableState,
                        )
                    }.persistingStateIn(this, sharing)

                    val id = alert.alertId
                    val item = cipher.toVaultListItem(
                        copy = copy,
                        translator = this@watchtowerNewAlertsStateProducer,
                        getTotpCode = getTotpCode,
                        concealFields = data.config.concealFields,
                        appIcons = data.config.appIcons,
                        websiteIcons = data.config.websiteIcons,
                        organizationsById = emptyMap(),
                        localStateFlow = localStateFlow,
                        onClick = {
                            VaultItem2.Item.Action.Go(
                                onClick = ::onClick
                                    .partially1(el),
                            )
                        },
                        onClickAttachment = {
                            null
                        },
                        onClickPasskey = {
                            null
                        },
                        onClickPassword = {
                            null
                        },
                    ).copy(
                        passkeys = persistentListOf(),
                        attachments2 = persistentListOf(),
                        token = null,
                    )
                    val date = dateFormatter
                        .formatDateTime(alert.reportedAt)
                    WatchtowerNewAlertsState.Item.Alert(
                        id = id,
                        item = item,
                        cipher = cipher,
                        type = alert.type,
                        alert = alert,
                        read = alert.read,
                        date = date,
                    )
                }
        }
    val itemsFlow = itemsRawFlow
        .map { raw ->
            val decorator =
                ItemDecoratorDate<WatchtowerNewAlertsState.Item, WatchtowerNewAlertsState.Item.Alert>(
                    dateFormatter = dateFormatter,
                    selector = { it.alert.reportedAt },
                    factory = { id, text ->
                        WatchtowerNewAlertsState.Item.Section(
                            id = id,
                            text = TextHolder.Value(text),
                        )
                    },
                )
            val out = mutableListOf<WatchtowerNewAlertsState.Item>()
            raw.forEachWithDecorUniqueSectionsOnly(
                decorator = decorator,
                tag = "WatchtowerNewAlerts",
                provideItemId = WatchtowerNewAlertsState.Item::id,
            ) { item ->
                out += item
            }

            val itemsReShaped = out
                .mapListShape()
                .toImmutableList()
            itemsReShaped
        }

    return itemsFlow
        .map { items ->
            val state = WatchtowerNewAlertsState(
                selection = null,
                options = persistentListOf(),
                items = items,
                onMarkAllRead = ::onMarkAllRead,
            )
            Loadable.Ok(state)
        }
}
