package com.artemchep.keyguard.feature.keyguardsync.devices

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Delete
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DKeyguardDevice
import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStorageStatus
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.auth.keyguard.awaitKeyguardAuthorityPassword
import com.artemchep.keyguard.feature.auth.keyguard.pairing.pairingMethodActions
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.awaitConfirmation
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_device_added_by
import com.artemchep.keyguard.res.app_link_device_added_on
import com.artemchep.keyguard.res.app_link_device_connected_via
import com.artemchep.keyguard.res.app_link_device_never_seen
import com.artemchep.keyguard.res.app_link_remove_confirm_added_text
import com.artemchep.keyguard.res.app_link_remove_confirm_readd_note
import com.artemchep.keyguard.res.app_link_remove_confirm_title
import com.artemchep.keyguard.res.app_link_status_devices_up_to_date_plural
import com.artemchep.keyguard.res.remove
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/** A device the screen lists, with its "added" text, which only changes with the devices. */
private data class KeyguardListedDevice(
    val device: DKeyguardDevice,
    val addedText: String,
)

private data class KeyguardDevicesSnapshot(
    val devices: List<DKeyguardDevice>,
    /** The active devices and this one, this one first. */
    val listed: List<KeyguardListedDevice>,
    val peers: Map<String, DKeyguardPeerStatus>,
    val relay: DKeyguardRelayStatus,
    val storage: DKeyguardRelayStorageStatus?,
)

/**
 * The devices of the sync group of the [target].
 *
 * @param getCanAddDevice emits whether this device may add devices; adding may need Premium.
 */
@Suppress("LongMethod", "LongParameterList") // One action per device menu item and per dialog.
suspend fun RememberStateFlowScope.keyguardDevicesStateProducer(
    target: KeyguardDevicesTarget,
    useCases: KeyguardDevicesUseCases,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    confirmAccessByPassword: ConfirmAccessByPasswordUseCase,
    getCanAddDevice: () -> Flow<Boolean>,
): Flow<Loadable<KeyguardDevicesState>> {
    val groupId = target.groupId
    val texts = target.texts
    val stateTexts = KeyguardDevicesState.Texts(
        addDeviceTitle = translate(texts.addDeviceTitle),
        leaveTitle = translate(texts.leaveTitle),
        leaveText = translate(texts.leaveText),
    )
    val actionExecutor = screenExecutor()

    suspend fun onRemove(device: DKeyguardDevice, devices: List<DKeyguardDevice>) {
        val io = awaitRemovalIo(
            device = device,
            devices = devices,
            text = translate(texts.removeConfirmText, device.label),
            useCases = useCases,
            groupId = groupId,
            dateFormatter = dateFormatter,
            confirmationRouteFactory = confirmationRouteFactory,
            confirmAccessByPassword = confirmAccessByPassword,
        ) ?: return
        actionExecutor.execute(io)
    }

    /** Leaves the group; a device that is still [active] signs its leaving with the password. */
    suspend fun onLeave(active: Boolean) {
        val io = awaitLeaveIo(
            active = active,
            texts = texts,
            useCases = useCases,
            groupId = groupId,
            confirmationRouteFactory = confirmationRouteFactory,
            confirmAccessByPassword = confirmAccessByPassword,
        ) ?: return
        actionExecutor.execute(io)
    }

    fun onAddDevice(scan: Boolean) {
        navigate(NavigationIntent.NavigateToRoute(target.addDeviceRoute(scan)))
    }

    val addDeviceActions = pairingMethodActions(::onAddDevice)

    // The order and the "added" texts depend on the devices only, not on their status.
    val devicesFlow = useCases.getKeyguardDevices(groupId)
        .map { devices ->
            val listed = devices
                .filter { !it.revoked || it.isThisDevice }
                .sortedWith(
                    compareByDescending<DKeyguardDevice> { it.isThisDevice }
                        .thenBy { it.label.lowercase() }
                        .thenBy { it.id },
                )
                .map { device ->
                    KeyguardListedDevice(
                        device = device,
                        addedText = keyguardDeviceAddedText(device, devices, dateFormatter),
                    )
                }
            devices to listed
        }
    val snapshotFlow = combine(
        devicesFlow,
        useCases.getKeyguardSyncStatus(groupId),
    ) { (devices, listed), status ->
        KeyguardDevicesSnapshot(
            devices = devices,
            listed = listed,
            peers = status.peers,
            relay = status.relay,
            storage = status.storage,
        )
    }
    return combine(
        snapshotFlow,
        getCanAddDevice(),
    ) { snapshot, canAddDevice ->
        val summary = keyguardDevicesSummary(
            devices = snapshot.devices,
            peers = snapshot.peers,
            relay = snapshot.relay,
        )
        val devices = snapshot.listed
            .map { (device, addedText) ->
                val canRemove = !device.isThisDevice && !summary.removed
                keyguardDeviceState(
                    device = device,
                    peer = snapshot.peers[device.id],
                    addedText = addedText,
                    onRemove = onClick { onRemove(device, snapshot.devices) }
                        .takeIf { canRemove },
                )
            }
        val header = keyguardDevicesHeader(
            summary = summary,
            relay = snapshot.relay.state,
            storage = snapshot.storage,
            dateFormatter = dateFormatter,
        )
        val state = KeyguardDevicesState(
            header = header,
            removed = summary.removed,
            removedByRemoved = summary.removedByRemoved,
            devices = devices.toPersistentList(),
            addDeviceActions = addDeviceActions
                .takeIf { canAddDevice && !summary.removed }
                ?: persistentListOf(),
            onLeave = onClick { onLeave(active = !summary.removed) },
            texts = stateTexts,
        )
        Loadable.Ok(state)
    }
}

/** Says when the [device] was added, and by which of the [devices] if it is one of them. */
private suspend fun TranslatorScope.keyguardDeviceAddedText(
    device: DKeyguardDevice,
    devices: List<DKeyguardDevice>,
    dateFormatter: DateFormatter,
): String {
    val date = dateFormatter.formatDate(device.addedAt)
    val addedBy = devices
        .firstOrNull { it.id == device.addedBy }
        ?.label
    return if (addedBy != null) {
        translate(Res.string.app_link_device_added_by, date, addedBy)
    } else {
        translate(Res.string.app_link_device_added_on, date)
    }
}

/**
 * Asks to remove the [device], with the devices of [devices] it added that the user picks,
 * then for the master password: removing a device needs the authority key, which only the
 * password opens. Returns the removal, or `null` if the user stopped.
 */
// The dependencies of the dialogs.
@Suppress("LongParameterList")
private suspend fun RememberStateFlowScope.awaitRemovalIo(
    device: DKeyguardDevice,
    devices: List<DKeyguardDevice>,
    text: String,
    useCases: KeyguardDevicesUseCases,
    groupId: P2pGroupId,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    confirmAccessByPassword: ConfirmAccessByPasswordUseCase,
): IO<Unit>? {
    // A stolen device that knew the password may have added some.
    val added = devices
        .filter { it.addedBy == device.id && !it.revoked && !it.isThisDevice }
    val deviceIds = awaitRemoval(
        confirmationRouteFactory = confirmationRouteFactory,
        device = device,
        added = added,
        text = text,
        addedText = { date -> translate(Res.string.app_link_device_added_on, dateFormatter.formatDate(date)) },
    )
    val password = deviceIds?.let {
        awaitKeyguardAuthorityPassword(
            confirmationRouteFactory = confirmationRouteFactory,
            confirmAccessByPassword = confirmAccessByPassword,
        )
    }
    return if (deviceIds != null && password != null) {
        useCases.revokeKeyguardDevice(groupId, deviceIds, password)
    } else {
        null
    }
}

/**
 * Asks to leave the group and, while this device is [active], for the master password that
 * opens its authority key. Returns the leaving, or `null` if the user stopped.
 */
// The dependencies of the dialogs.
@Suppress("LongParameterList")
private suspend fun RememberStateFlowScope.awaitLeaveIo(
    active: Boolean,
    texts: KeyguardDevicesTexts,
    useCases: KeyguardDevicesUseCases,
    groupId: P2pGroupId,
    confirmationRouteFactory: ConfirmationRouteFactory,
    confirmAccessByPassword: ConfirmAccessByPasswordUseCase,
): IO<Unit>? {
    val confirmed = awaitConfirmation(
        confirmationRouteFactory = confirmationRouteFactory,
        args = ConfirmationRoute.Args(
            icon = icon(Icons.AutoMirrored.Outlined.Logout),
            title = translate(texts.leaveTitle),
            message = translate(texts.leaveConfirmText),
        ),
    ) is ConfirmationResult.Confirm
    // A device the group removed leaves without the password.
    val password = if (confirmed && active) {
        awaitKeyguardAuthorityPassword(
            confirmationRouteFactory = confirmationRouteFactory,
            confirmAccessByPassword = confirmAccessByPassword,
        )
    } else {
        null
    }
    return when {
        !confirmed || (active && password == null) -> null
        else -> useCases.leaveKeyguardGroup(groupId, password)
    }
}

/**
 * Asks to remove the [device], and which of the devices it [added] to remove with it.
 * Returns the ids of the devices to remove, or `null` if the user said no.
 */
private suspend fun RememberStateFlowScope.awaitRemoval(
    confirmationRouteFactory: ConfirmationRouteFactory,
    device: DKeyguardDevice,
    added: List<DKeyguardDevice>,
    text: String,
    addedText: suspend (Instant) -> String,
): Set<String>? {
    val note = translate(Res.string.app_link_remove_confirm_readd_note)
    val message = if (added.isEmpty()) {
        "$text\n\n$note"
    } else {
        "$text\n\n$note\n\n" + translate(Res.string.app_link_remove_confirm_added_text, device.label)
    }
    val items = added.map { child ->
        ConfirmationRoute.Args.Item.BooleanItem(
            key = REMOVE_ADDED_KEY_PREFIX + child.id,
            title = child.label,
            text = addedText(child.addedAt),
        )
    }
    val confirmed = awaitConfirmation(
        confirmationRouteFactory = confirmationRouteFactory,
        args = ConfirmationRoute.Args(
            icon = icon(Icons.Outlined.Delete),
            title = translate(Res.string.app_link_remove_confirm_title),
            message = message,
            items = items,
        ),
    ) as? ConfirmationResult.Confirm
        ?: return null
    val also = added
        .filter { confirmed.data[REMOVE_ADDED_KEY_PREFIX + it.id] == true }
        .map { it.id }
    return setOf(device.id) + also
}

private const val REMOVE_ADDED_KEY_PREFIX = "remove_added:"

private suspend fun TranslatorScope.keyguardDevicesHeader(
    summary: KeyguardDevicesSummary,
    relay: DKeyguardRelayStatus.State,
    storage: DKeyguardRelayStorageStatus?,
    dateFormatter: DateFormatter,
): KeyguardDevicesState.Header {
    val upToDateText = if (summary.total > 0) {
        translate(
            Res.plurals.app_link_status_devices_up_to_date_plural,
            summary.total,
            summary.upToDate,
            summary.total,
        )
    } else {
        null
    }
    return KeyguardDevicesState.Header(
        health = keyguardDevicesHealth(relay, summary),
        relayText = translateKeyguardRelayStatus(relay),
        upToDate = summary.upToDate,
        total = summary.total,
        upToDateText = upToDateText,
        storageText = storage?.let { translateKeyguardRelayStorageStatus(it, dateFormatter) },
    )
}

private suspend fun TranslatorScope.keyguardDeviceState(
    device: DKeyguardDevice,
    peer: DKeyguardPeerStatus?,
    addedText: String,
    onRemove: (() -> Unit)?,
): KeyguardDevicesState.Device {
    val dropdown = buildContextItems {
        if (onRemove != null) {
            this += FlatItemAction(
                id = "remove",
                icon = Icons.Outlined.Delete,
                title = Res.string.remove.wrap(),
                danger = true,
                onClick = onRemove,
            )
        }
    }
    // This device has no connection to itself.
    val connection = if (device.isThisDevice) {
        null
    } else {
        peer?.connection ?: DKeyguardPeerStatus.Connection.Offline
    }
    val lastSeen = connection?.let { keyguardDeviceLastSeen(it, peer) }
    val statusText = connection
        ?.takeIf { lastSeen == null }
        ?.let { keyguardDeviceStatusText(it) }
    return KeyguardDevicesState.Device(
        id = device.id,
        label = device.label,
        platform = device.platform,
        fingerprint = device.fingerprint,
        isThisDevice = device.isThisDevice,
        addedText = addedText,
        health = connection?.let(::keyguardDeviceHealth),
        statusText = statusText,
        lastSeen = lastSeen,
        onRemove = onRemove,
        dropdown = dropdown,
    )
}

/** When the devices last had a session, while they have none over the [connection]. */
private suspend fun TranslatorScope.keyguardDeviceLastSeen(
    connection: DKeyguardPeerStatus.Connection,
    peer: DKeyguardPeerStatus?,
): KeyguardDevicesState.LastSeen? {
    val at = peer?.lastSeenAt
    val transport = peer?.lastTransport
    if (connection is DKeyguardPeerStatus.Connection.Connected || at == null || transport == null) {
        return null
    }
    return KeyguardDevicesState.LastSeen(
        at = at,
        transportText = translate(transport.textRes),
    )
}

/** How the device is connected over the [connection], or that it never was. */
private suspend fun TranslatorScope.keyguardDeviceStatusText(
    connection: DKeyguardPeerStatus.Connection,
): String = when (connection) {
    is DKeyguardPeerStatus.Connection.Connected -> translate(
        Res.string.app_link_device_connected_via,
        translate(connection.transport.textRes),
    )

    DKeyguardPeerStatus.Connection.Connecting,
    DKeyguardPeerStatus.Connection.Offline,
    -> translate(Res.string.app_link_device_never_seen)
}
