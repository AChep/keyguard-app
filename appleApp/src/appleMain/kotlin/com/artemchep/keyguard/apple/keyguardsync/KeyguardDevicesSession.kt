package com.artemchep.keyguard.apple.keyguardsync

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.applink.appLinkDevicesStateProducer
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesState
import com.artemchep.keyguard.feature.keyguardsync.devices.keyguardDevicesUseCases
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import org.koin.core.scope.Scope

/**
 * The devices of one sync group, such as the linked apps, driven by the
 * shared Compose producer. Removing and leaving ask first, through the dialog
 * bridge; Swift opens the pairing screen itself. Main-confined.
 */
class KeyguardDevicesSession internal constructor(
    private val ctx: CoreContext,
    private val target: Target,
) {
    /** The sync group of the screen. */
    internal sealed interface Target {
        /** The linked apps. */
        data object AppLink : Target
    }

    internal var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null

    private val session = DetailSession<KeyguardDevicesSnapshot, KeyguardDevicesActions>()

    /** Emits [KeyguardDevicesSnapshot.empty] while the vault is locked. */
    fun observe(
        onChange: (KeyguardDevicesSnapshot) -> Unit,
    ): KeyguardCancellable = session.observe(onChange) { publish ->
        ctx.launchSessionObserver(
            onLocked = { publish(KeyguardDevicesSnapshot.empty, KeyguardDevicesActions(null)) },
        ) { state ->
            val sessionKoin = state.sessionKoin
            val screen = ctx.koin
                .newHeadlessStateFlowScope(
                    "keyguard_devices",
                    this,
                    navigationInterceptorProvider?.invoke(sessionKoin),
                )
            val producerFlow = when (target) {
                Target.AppLink -> screen.appLinkDevicesStateProducer(
                    useCases = sessionKoin.keyguardDevicesUseCases(),
                    dateFormatter = sessionKoin.get(),
                    confirmationRouteFactory = sessionKoin.get(),
                    confirmAccessByPassword = sessionKoin.get(),
                    getPurchased = sessionKoin.get(),
                    getAppLinkThisDevice = sessionKoin.get(),
                )
            }
            producerFlow
                .mapNotNull { it.getOrNull() }
                .throttleLatest()
                .map { input ->
                    buildSnapshot(input) to KeyguardDevicesActions(input)
                }
                .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
        }
    }

    fun close() = session.close()

    private fun buildSnapshot(
        state: KeyguardDevicesState,
    ): KeyguardDevicesSnapshot {
        val header = state.header
        return KeyguardDevicesSnapshot(
            loaded = true,
            header = KeyguardDevicesSnapshot.Header(
                status = header.health.toStatus(),
                relayText = header.relayText,
                upToDate = header.upToDate,
                total = header.total,
                upToDateText = header.upToDateText,
                storageText = header.storageText,
            ),
            removed = state.removed,
            removedByRemoved = state.removedByRemoved,
            devices = state.devices.map { device -> device.toSnapshot() },
            canAddDevice = state.addDeviceActions.isNotEmpty(),
            addDeviceTitle = state.texts.addDeviceTitle,
            canLeave = state.onLeave != null,
            leaveTitle = state.texts.leaveTitle,
            leaveText = state.texts.leaveText,
        )
    }

    /** The screen's callbacks, published atomically with the snapshot built from the same [state]. */
    private class KeyguardDevicesActions(val state: KeyguardDevicesState?) {
        fun device(id: String) = state?.devices?.firstOrNull { it.id == id }
    }

    /** Asks, then removes the device [id] from the sync group. */
    fun remove(id: String) = session.withActions { it.device(id)?.onRemove?.invoke() }

    /** Asks, then takes this device out of the sync group. */
    fun leave() = session.withActions { it.state?.onLeave?.invoke() }
}

private fun KeyguardDevicesState.Device.toSnapshot() = KeyguardDevicesSnapshot.Device(
    id = id,
    label = label,
    platform = platform,
    fingerprint = fingerprint,
    isThisDevice = isThisDevice,
    addedText = addedText,
    status = health?.toStatus(),
    statusText = statusText,
    // Swift formats the time since the last session, so it stays current.
    lastSeenAtMs = lastSeen?.at?.toEpochMilliseconds(),
    lastTransportText = lastSeen?.transportText,
    canRemove = onRemove != null,
)

private fun KeyguardDevicesState.Health.toStatus(): KeyguardDevicesSnapshot.Status = when (this) {
    KeyguardDevicesState.Health.ERROR -> KeyguardDevicesSnapshot.Status.ERROR
    KeyguardDevicesState.Health.OK -> KeyguardDevicesSnapshot.Status.OK
    KeyguardDevicesState.Health.NEUTRAL -> KeyguardDevicesSnapshot.Status.NEUTRAL
    KeyguardDevicesState.Health.WARNING -> KeyguardDevicesSnapshot.Status.WARNING
}
