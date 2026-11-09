package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.RevokeKeyguardDevice
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.QueueP2pSync
import com.artemchep.keyguard.provider.keyguard.requireP2pMembership
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Signs the revocations of the devices with the authority key of this device, adds them to
 * the stored roster and queues a sync, which shares the roster with the other devices.
 *
 * The revocation of a device seals the devices it admitted, see
 * [com.artemchep.keyguard.feature.p2psync.model.Revocation.admitted]: all of them but the
 * ones removed with it.
 */
class RevokeKeyguardDeviceImpl(
    private val db: VaultDatabaseManager,
    private val crypto: P2pCrypto,
    private val authorityKeys: KeyguardAuthorityKeys,
    private val queueSync: QueueP2pSync,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val now: () -> Instant = { Clock.System.now() },
) : RevokeKeyguardDevice {
    companion object {
        private const val TAG = "RevokeKeyguardDevice"
    }

    override fun invoke(
        groupId: P2pGroupId,
        deviceIds: Set<String>,
        password: String,
    ): IO<Unit> = ioEffect(Dispatchers.Default) {
        require(deviceIds.isNotEmpty()) {
            "No device to remove."
        }
        // The key derivation is slow: open the key before taking the database.
        authorityKeys.openOwn(db, groupId, password).use { key ->
            db
                .mutate(TAG) { database ->
                    val membership = database.requireP2pMembership(groupId)
                    val self = membership.device.deviceId
                    require(self !in deviceIds) {
                        "This device can not remove itself."
                    }
                    key.checkCurrent(membership)

                    val store = KeyguardSyncStore(database, groupId.id)
                    val roster = store.loadValidated(membership, crypto)
                    checkNotNull(roster.self) {
                        "This device is not an active device of the group."
                    }
                    require(deviceIds.all(roster.activity::isActive)) {
                        "The device is not an active device of the group."
                    }

                    val clock = roster.clock(now)
                    val signer = RosterSigner(crypto, membership.groupId)
                    val revocations = deviceIds.sorted().map { deviceId ->
                        signer.revoke(
                            roster = roster.validated,
                            deviceId = deviceId,
                            at = clock.now(),
                            by = self,
                            authorityPrivateKey = key.seed,
                            kept = { it.id !in deviceIds },
                        )
                    }
                    val stored = roster.stored
                    store.saveRoster(stored.copy(revoked = stored.revoked + revocations))
                }
                .bind()
        }
        // Tell the other devices.
        queueSync(groupId)
            .launchIn(windowCoroutineScope)
    }
}
