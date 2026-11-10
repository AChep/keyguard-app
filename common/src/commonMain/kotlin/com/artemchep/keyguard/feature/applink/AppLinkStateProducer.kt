package com.artemchep.keyguard.feature.applink

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.DAppLinkThisDevice
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAppLinkState
import com.artemchep.keyguard.common.usecase.GetAppLinkSyncCategories
import com.artemchep.keyguard.common.usecase.GetAppLinkThisDevice
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.PutAppLinkDeviceLabel
import com.artemchep.keyguard.common.usecase.PutAppLinkSyncCategory
import com.artemchep.keyguard.feature.auth.keyguard.pairing.pairingMethodActions
import com.artemchep.keyguard.feature.auth.keyguard.translateKeyguardRelayName
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesState
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesTarget
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesTexts
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesUseCases
import com.artemchep.keyguard.feature.keyguardsync.devices.keyguardDevicesStateProducer
import com.artemchep.keyguard.feature.keyguardsync.devices.keyguardDevicesUseCases
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.p2psync.model.DeviceRecordRules
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.provider.keyguard.applink.canLinkApps
import com.artemchep.keyguard.provider.keyguard.keyguardPlatformName
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_connection_lan_off
import com.artemchep.keyguard.res.app_link_connection_title
import com.artemchep.keyguard.res.app_link_device_name_error_invalid
import com.artemchep.keyguard.res.app_link_device_name_label
import com.artemchep.keyguard.res.app_link_device_name_rename_text
import com.artemchep.keyguard.res.app_link_link_another_device
import com.artemchep.keyguard.res.app_link_remove_confirm_text
import com.artemchep.keyguard.res.app_link_unlink_confirm_text
import com.artemchep.keyguard.res.app_link_unlink_text
import com.artemchep.keyguard.res.app_link_unlink_title
import com.artemchep.keyguard.res.emailrelay_list_header_title
import com.artemchep.keyguard.res.rename
import com.artemchep.keyguard.res.urlblock_list_header_title
import com.artemchep.keyguard.res.urloverride_list_header_title
import com.artemchep.keyguard.res.wordlist_list_header_title
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.koin.compose.currentKoinScope

/** The devices of the link of this app, as the devices screen shows them. */
val AppLinkDevicesTarget = KeyguardDevicesTarget(
    groupId = P2pGroupId.AppLink,
    addDeviceRoute = ::AppLinkPairRoute,
    texts = KeyguardDevicesTexts(
        addDeviceTitle = Res.string.app_link_link_another_device,
        removeConfirmText = Res.string.app_link_remove_confirm_text,
        leaveTitle = Res.string.app_link_unlink_title,
        leaveText = Res.string.app_link_unlink_text,
        leaveConfirmText = Res.string.app_link_unlink_confirm_text,
    ),
)

@Composable
fun produceAppLinkScreenState(): Loadable<AppLinkState> = with(currentKoinScope()) {
    produceAppLinkScreenState(
        useCases = AppLinkUseCases(
            getAppLinkState = get(),
            getAppLinkSyncCategories = get(),
            putAppLinkSyncCategory = get(),
            getAppLinkThisDevice = get(),
            putAppLinkDeviceLabel = get(),
            getPurchased = get(),
        ),
        confirmationRouteFactory = get(),
    )
}

/** The use cases the linked devices screen reads and changes this device with. */
data class AppLinkUseCases(
    val getAppLinkState: GetAppLinkState,
    val getAppLinkSyncCategories: GetAppLinkSyncCategories,
    val putAppLinkSyncCategory: PutAppLinkSyncCategory,
    val getAppLinkThisDevice: GetAppLinkThisDevice,
    val putAppLinkDeviceLabel: PutAppLinkDeviceLabel,
    val getPurchased: GetPurchased,
)

@Composable
fun produceAppLinkScreenState(
    useCases: AppLinkUseCases,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Loadable<AppLinkState> = produceScreenState(
    initial = Loadable.Loading,
    key = "app_link",
    args = arrayOf(
        useCases,
        confirmationRouteFactory,
    ),
) {
    appLinkStateProducer(
        useCases = useCases,
        confirmationRouteFactory = confirmationRouteFactory,
    )
}

/**
 * Emits whether this app may link devices with the relay of [thisDeviceFlow] and its
 * purchase status.
 */
internal fun appLinkCanLinkFlow(
    getPurchased: GetPurchased,
    thisDeviceFlow: Flow<DAppLinkThisDevice>,
): Flow<Boolean> = combine(
    getPurchased(),
    thisDeviceFlow,
) { premium, device ->
    canLinkApps(premium, device.network)
}

/**
 * Asks for a new name of this device, [label] now, and hands it to [onRename]. The name must
 * be one every device takes, see [DeviceRecordRules.isValidLabel].
 */
private suspend fun RememberStateFlowScope.appLinkRenameIntent(
    confirmationRouteFactory: ConfirmationRouteFactory,
    label: String,
    onRename: (String) -> Unit,
): NavigationIntent {
    // The check runs on every keystroke, so its text is formatted once.
    val invalid = TextHolder.Value(
        translate(Res.string.app_link_device_name_error_invalid, DeviceRecordRules.MAX_LABEL_LENGTH),
    )
    return createConfirmationDialogIntent(
        confirmationRouteFactory = confirmationRouteFactory,
        icon = icon(Icons.Outlined.Edit),
        item = ConfirmationRoute.Args.Item.StringItem(
            key = RENAME_LABEL_KEY,
            title = translate(Res.string.app_link_device_name_label),
            value = label,
            description = translate(Res.string.app_link_device_name_rename_text),
            canBeEmpty = false,
        ),
        title = translate(Res.string.rename),
        validate = { values ->
            val newLabel = (values[RENAME_LABEL_KEY] as? String).orEmpty().trim()
            // A blank name has an error of its own.
            val valid = newLabel.isEmpty() || DeviceRecordRules.isValidLabel(newLabel)
            if (valid) emptyMap() else mapOf(RENAME_LABEL_KEY to invalid)
        },
        onSuccess = onRename,
    )
}

private const val RENAME_LABEL_KEY = "label"

/** What this device syncs; a switch can not change while another change is saved. */
private fun appLinkCategoriesFlow(
    getAppLinkSyncCategories: GetAppLinkSyncCategories,
    isSavingFlow: Flow<Boolean>,
    onCategoryChange: (DAppLinkSyncCategory, Boolean) -> Unit,
) = combine(
    getAppLinkSyncCategories(),
    isSavingFlow,
) { enabled, isSaving ->
    DAppLinkSyncCategory.entries
        .map { category ->
            AppLinkState.Category(
                category = category,
                title = category.title(),
                checked = enabled[category] ?: true,
                onCheckedChange = if (isSaving) {
                    null
                } else {
                    // lambda
                    { value: Boolean ->
                        onCategoryChange(category, value)
                    }
                },
            )
        }
        .toPersistentList()
}

/** What this device's section does, as a menu. */
private fun appLinkThisDeviceDropdown(
    onRename: () -> Unit,
    onConnection: () -> Unit,
) = buildContextItems {
    this += FlatItemAction(
        id = "rename",
        icon = Icons.Outlined.Edit,
        title = Res.string.rename.wrap(),
        onClick = onRename,
    )
    this += FlatItemAction(
        id = "connection",
        icon = Icons.Outlined.Dns,
        title = Res.string.app_link_connection_title.wrap(),
        onClick = onConnection,
    )
}

fun RememberStateFlowScope.appLinkStateProducer(
    useCases: AppLinkUseCases,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Flow<Loadable<AppLinkState>> {
    val executor = screenExecutor()
    val renameExecutor = screenExecutor()
    // Both this device's card and whether it may link read this device.
    val appLinkThisDeviceFlow = useCases.getAppLinkThisDevice()
        .shareInScreenScope()
    val canLinkFlow = appLinkCanLinkFlow(useCases.getPurchased, appLinkThisDeviceFlow)

    fun onPair(scan: Boolean) {
        val route = AppLinkPairRoute(scan = scan)
        navigate(NavigationIntent.NavigateToRoute(route))
    }

    val linkActions = pairingMethodActions(::onPair)

    fun onCategoryChange(category: DAppLinkSyncCategory, enabled: Boolean) {
        val io = useCases.putAppLinkSyncCategory(category, enabled)
        executor.execute(io)
    }

    suspend fun onRename(label: String) {
        val intent = appLinkRenameIntent(confirmationRouteFactory, label) { newLabel ->
            val io = useCases.putAppLinkDeviceLabel(newLabel)
            renameExecutor.execute(io)
        }
        navigate(intent)
    }

    fun onConnection() {
        navigate(NavigationIntent.NavigateToRoute(AppLinkConnectionRoute))
    }

    val platform = CurrentPlatform.keyguardPlatformName()
    val thisDeviceFlow = appLinkThisDeviceFlow
        .map { device ->
            val onRenameClick = onClick { onRename(device.label) }
            AppLinkState.ThisDevice(
                label = device.label,
                platform = platform,
                relayText = translateKeyguardRelayName(device.network),
                lanText = translate(Res.string.app_link_connection_lan_off)
                    .takeUnless { device.network.allowLan },
                onRename = onRenameClick,
                onConnection = ::onConnection,
                dropdown = appLinkThisDeviceDropdown(
                    onRename = onRenameClick,
                    onConnection = ::onConnection,
                ),
            )
        }
    val categoriesFlow = appLinkCategoriesFlow(
        getAppLinkSyncCategories = useCases.getAppLinkSyncCategories,
        isSavingFlow = executor.isExecutingFlow,
        onCategoryChange = ::onCategoryChange,
    )
    return combine(
        useCases.getAppLinkState(),
        thisDeviceFlow,
        categoriesFlow,
        canLinkFlow,
    ) { linkState, thisDevice, categories, canLink ->
        val content = when (linkState) {
            DAppLinkState.NotLinked,
            DAppLinkState.Alone,
            -> AppLinkState.Content.Unlinked(
                linkActions = linkActions.takeIf { canLink } ?: persistentListOf(),
            )

            is DAppLinkState.Linked,
            DAppLinkState.Removed,
            -> AppLinkState.Content.Linked
        }
        val state = AppLinkState(
            content = content,
            thisDevice = thisDevice,
            categories = categories,
            premiumRequired = !canLink,
        )
        Loadable.Ok(state)
    }
}

@Composable
fun produceAppLinkDevicesScreenState(): Loadable<KeyguardDevicesState> = with(currentKoinScope()) {
    val useCases = keyguardDevicesUseCases()
    val dateFormatter = get<DateFormatter>()
    val confirmationRouteFactory = get<ConfirmationRouteFactory>()
    val confirmAccessByPassword = get<ConfirmAccessByPasswordUseCase>()
    val getPurchased = get<GetPurchased>()
    val getAppLinkThisDevice = get<GetAppLinkThisDevice>()
    produceScreenState(
        initial = Loadable.Loading,
        key = "keyguard_devices:${AppLinkDevicesTarget.groupId.id}",
        args = arrayOf(
            useCases,
            dateFormatter,
            confirmationRouteFactory,
            confirmAccessByPassword,
            getPurchased,
            getAppLinkThisDevice,
        ),
    ) {
        appLinkDevicesStateProducer(
            useCases = useCases,
            dateFormatter = dateFormatter,
            confirmationRouteFactory = confirmationRouteFactory,
            confirmAccessByPassword = confirmAccessByPassword,
            getPurchased = getPurchased,
            getAppLinkThisDevice = getAppLinkThisDevice,
        )
    }
}

/**
 * The devices of the link of this app. Linking needs a purchase or a self-hosted relay;
 * removing a device needs the master password.
 */
suspend fun RememberStateFlowScope.appLinkDevicesStateProducer(
    useCases: KeyguardDevicesUseCases,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    confirmAccessByPassword: ConfirmAccessByPasswordUseCase,
    getPurchased: GetPurchased,
    getAppLinkThisDevice: GetAppLinkThisDevice,
): Flow<Loadable<KeyguardDevicesState>> = keyguardDevicesStateProducer(
    target = AppLinkDevicesTarget,
    useCases = useCases,
    dateFormatter = dateFormatter,
    confirmationRouteFactory = confirmationRouteFactory,
    confirmAccessByPassword = confirmAccessByPassword,
    getCanAddDevice = {
        appLinkCanLinkFlow(getPurchased, getAppLinkThisDevice())
    },
)

private fun DAppLinkSyncCategory.title(): TextHolder = when (this) {
    DAppLinkSyncCategory.EMAIL_FORWARDERS -> TextHolder.Res(Res.string.emailrelay_list_header_title)
    DAppLinkSyncCategory.WORDLISTS -> TextHolder.Res(Res.string.wordlist_list_header_title)
    DAppLinkSyncCategory.URL_OVERRIDES -> TextHolder.Res(Res.string.urloverride_list_header_title)
    DAppLinkSyncCategory.URL_BLOCKS -> TextHolder.Res(Res.string.urlblock_list_header_title)
}
