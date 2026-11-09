package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.PutKeyguardDeviceLabel
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.model.DeviceRecordRules
import com.artemchep.keyguard.provider.keyguard.P2pMembershipStore
import com.artemchep.keyguard.provider.keyguard.QueueP2pSync
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Renames this device. The name goes into the sync group membership and,
 * while the device is active, into its roster record, which the device signs again with
 * a newer update stamp. A sync then hands the record to the other devices.
 *
 * The name, trimmed, must be valid by the rules every device checks, see
 * [DeviceRecordRules.isValidLabel]: the other devices drop a record with another one.
 */
class PutKeyguardDeviceLabelImpl(
    private val db: VaultDatabaseManager,
    private val membershipStore: P2pMembershipStore,
    private val crypto: P2pCrypto,
    private val queueSync: QueueP2pSync,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val now: () -> Instant = { Clock.System.now() },
) : PutKeyguardDeviceLabel {
    companion object {
        private const val TAG = "PutKeyguardDeviceLabel"
    }

    override fun invoke(
        groupId: P2pGroupId,
        label: String,
    ): IO<Unit> = ioEffect(Dispatchers.Default) {
        val newLabel = requireValidDeviceLabel(label)
        val renamed = db
            .mutate(TAG) { database ->
                val membership = membershipStore.update(database, groupId) { membership ->
                    membership.copy(
                        device = membership.device.copy(label = newLabel),
                    )
                }
                database.renameRosterRecord(groupId.id, membership, newLabel)
            }
            .bind()
        // Tell the other devices.
        if (renamed) {
            queueSync(groupId)
                .launchIn(windowCoroutineScope)
        }
    }

    /**
     * Signs the roster record of this device again with [label]. Returns `false` if the
     * record has that name already, or the device is not active in the group.
     */
    private fun Database.renameRosterRecord(
        groupId: String,
        membership: P2pMembership,
        label: String,
    ): Boolean = KeyguardSyncStore(this, groupId)
        .loadValidated(membership, crypto)
        .updateSelf(now, label = label)
}

/**
 * Returns the [label] trimmed.
 *
 * @throws IllegalArgumentException if it is not a valid name of a device, see
 *   [DeviceRecordRules.isValidLabel].
 */
internal fun requireValidDeviceLabel(label: String): String {
    val trimmed = label.trim()
    require(DeviceRecordRules.isValidLabel(trimmed)) {
        "The name of a device must be 1 to ${DeviceRecordRules.MAX_LABEL_LENGTH} characters, " +
            "without control characters."
    }
    return trimmed
}
