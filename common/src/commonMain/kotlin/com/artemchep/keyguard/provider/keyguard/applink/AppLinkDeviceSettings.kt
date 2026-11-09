package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getSerializable
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.core.store.bitwarden.normalized
import com.artemchep.keyguard.feature.p2psync.P2pJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The name of this device and how it reaches the other devices, kept for the times the app
 * is not linked. While it is, the membership holds them and wins; each change is saved to
 * both, and unlinking [keep]s the ones of the membership. So they are the same for every
 * link, and the user sets them before the first one.
 *
 * They live in the vault database, like the membership: the access token is a secret.
 */
class AppLinkDeviceSettings(
    private val store: VaultSettingsKeyValueStore,
) {
    companion object {
        private const val KEY_LABEL = "p2p.app.device.label"
        private const val KEY_NETWORK = "p2p.app.device.network"
    }

    private fun labelPreference() = store.getString(KEY_LABEL, "")

    private fun networkPreference() = store.getSerializable(
        json = P2pJson,
        key = KEY_NETWORK,
        defaultValue = P2pNetwork(),
    )

    /** Emits the saved name of this device, or `null` if there is none. */
    fun label(): Flow<String?> = labelPreference()
        .map { it.trim().ifEmpty { null } }
        .distinctUntilChanged()

    /** Emits the saved network settings; the default ones if there are none. */
    fun network(): Flow<P2pNetwork> = networkPreference()
        .distinctUntilChanged()

    fun setLabel(label: String): IO<Unit> = labelPreference()
        .setAndCommit(label.trim())

    fun setNetwork(network: P2pNetwork): IO<Unit> = networkPreference()
        .setAndCommit(network.normalized())

    /** Saves the name and the network settings of the [membership], for the next link. */
    fun keep(membership: P2pMembership): IO<Unit> = setLabel(membership.device.label)
        .flatMap { setNetwork(membership.network) }
}
