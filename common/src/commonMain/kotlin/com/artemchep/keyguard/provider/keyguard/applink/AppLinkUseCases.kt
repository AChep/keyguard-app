package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.DAppLinkThisDevice
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.usecase.GetAppLinkState
import com.artemchep.keyguard.common.usecase.GetAppLinkSyncCategories
import com.artemchep.keyguard.common.usecase.GetAppLinkThisDevice
import com.artemchep.keyguard.common.usecase.GetDefaultDeviceLabel
import com.artemchep.keyguard.common.usecase.PutAppLinkDeviceLabel
import com.artemchep.keyguard.common.usecase.PutAppLinkNetwork
import com.artemchep.keyguard.common.usecase.PutAppLinkSyncCategory
import com.artemchep.keyguard.common.usecase.PutKeyguardDeviceLabel
import com.artemchep.keyguard.common.usecase.PutKeyguardNetworkSettings
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.p2psync.model.DeviceRecordRules
import com.artemchep.keyguard.provider.bitwarden.usecase.requireValidDeviceLabel
import com.artemchep.keyguard.provider.keyguard.P2pMembershipStore
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

class GetAppLinkStateImpl(
    private val service: AppLinkService,
) : GetAppLinkState {
    override fun invoke(): Flow<DAppLinkState> = service.observeState()
}

class GetAppLinkSyncCategoriesImpl(
    private val switches: AppSyncSwitches,
) : GetAppLinkSyncCategories {
    override fun invoke(): Flow<Map<DAppLinkSyncCategory, Boolean>> = switches.enabled()
}

/**
 * Saves the switch and queues a sync pass. A category turned on asks the peers for their
 * state again over the [sessions] that run: this device dropped their entries of it while
 * it was off, see [AppLinkSyncPass].
 */
class PutAppLinkSyncCategoryImpl(
    private val switches: AppSyncSwitches,
    private val queueSync: () -> IO<Unit>,
    private val sessions: () -> KeyguardSessionLauncher,
) : PutAppLinkSyncCategory {
    override fun invoke(
        category: DAppLinkSyncCategory,
        enabled: Boolean,
    ): IO<Unit> = switches
        .set(category, enabled)
        .effectTap {
            if (enabled) sessions().requestResend(P2pGroupId.APP_LINK_ID)
        }
        .flatMap { queueSync() }
}

/** The membership of the link wins while there is one; then the saved settings. */
class GetAppLinkThisDeviceImpl(
    private val membershipStore: P2pMembershipStore,
    private val settings: AppLinkDeviceSettings,
    private val getDefaultDeviceLabel: GetDefaultDeviceLabel,
) : GetAppLinkThisDevice {
    override fun invoke(): Flow<DAppLinkThisDevice> {
        // Only a suggestion: the user renames the device.
        val defaultLabelFlow = flow {
            val label = getDefaultDeviceLabel()
                .handleError { "" }
                .bind()
            emit(label)
        }
        return combine(
            membershipStore.observe(P2pGroupId.AppLink),
            settings.label(),
            settings.network(),
            defaultLabelFlow,
        ) { membership, label, network, defaultLabel ->
            DAppLinkThisDevice(
                label = membership?.device?.label
                    ?: label
                    ?: defaultLabel,
                network = membership?.network
                    ?: network,
            )
        }.distinctUntilChanged()
    }
}

/**
 * Saves the name for the next link, and renames this device in the link if there is one.
 * The name, trimmed, must be valid, see [DeviceRecordRules.isValidLabel].
 */
class PutAppLinkDeviceLabelImpl(
    private val membershipStore: P2pMembershipStore,
    private val settings: AppLinkDeviceSettings,
    private val putKeyguardDeviceLabel: PutKeyguardDeviceLabel,
) : PutAppLinkDeviceLabel {
    override fun invoke(
        label: String,
    ): IO<Unit> = ioEffect {
        val newLabel = requireValidDeviceLabel(label)
        settings.setLabel(newLabel).bind()
        if (membershipStore.get(P2pGroupId.AppLink) != null) {
            putKeyguardDeviceLabel(P2pGroupId.AppLink, newLabel).bind()
        }
    }
}

/** Saves the settings for the next link, and changes them in the link if there is one. */
class PutAppLinkNetworkImpl(
    private val membershipStore: P2pMembershipStore,
    private val settings: AppLinkDeviceSettings,
    private val putKeyguardNetworkSettings: PutKeyguardNetworkSettings,
) : PutAppLinkNetwork {
    override fun invoke(
        network: P2pNetwork,
    ): IO<Unit> = ioEffect {
        // Each store normalizes the settings.
        settings.setNetwork(network).bind()
        if (membershipStore.get(P2pGroupId.AppLink) != null) {
            putKeyguardNetworkSettings(P2pGroupId.AppLink, network).bind()
        }
    }
}
