package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.LeaveKeyguardGroup
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKey
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionPublication
import com.artemchep.keyguard.provider.keyguard.sync.requireLocalRoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Takes this device out of the sync group: signs the revocation of this device with its
 * authority key, which the master password opens, adds it to the stored roster and hands
 * it to the devices the session is connected to, if one runs, waiting at most
 * [PUBLISH_TIMEOUT_MS]. Then takes this device out of the group with [dissolve]; the data
 * stays on this device.
 *
 * The devices that were not connected keep listing this device until it is removed there.
 *
 * @param retainLive keeps the live session leased before self-revocation releases its
 *   foreground hold, and captures the connected sessions to publish to.
 * @param dissolve forgets the group on this device.
 */
class LeaveKeyguardGroupImpl(
    private val db: VaultDatabaseManager,
    private val crypto: P2pCrypto,
    private val authorityKeys: KeyguardAuthorityKeys,
    private val dissolve: suspend (P2pGroupId) -> Unit,
    private val retainLive: suspend (groupId: String) -> KeyguardSessionPublication?,
    private val now: () -> Instant = { Clock.System.now() },
) : LeaveKeyguardGroup {
    companion object {
        private const val TAG = "LeaveKeyguardGroup"

        /** How long leaving waits for the connected devices to receive the revocation. */
        const val PUBLISH_TIMEOUT_MS: Long = 10_000L
    }

    override fun invoke(
        groupId: P2pGroupId,
        password: String?,
    ): IO<Unit> = ioEffect(Dispatchers.Default) {
        // The key derivation is slow: open the key before taking the database.
        val authority = authorityKeys.openOwnOrNull(db, groupId, password)
        var publication: KeyguardSessionPublication? = null
        try {
            val revoked = try {
                publication = retainLive(groupId.id)
                revokeSelf(groupId, authority)
            } finally {
                authority?.close()
            }
            if (revoked) {
                publication?.let { publishBestEffort(it) }
            }
            dissolve(groupId)
        } finally {
            withContext(NonCancellable) { publication?.close() }
        }
    }

    /**
     * Adds the revocation of this device to the stored roster, signed by its [authority]
     * key, or by its device key if it has none. Returns `false` if the device is not active
     * in the group.
     */
    private suspend fun revokeSelf(
        groupId: P2pGroupId,
        authority: KeyguardAuthorityKey?,
    ): Boolean = db
        .mutate(TAG) { database ->
            val roster = database.requireLocalRoster(groupId, crypto)
            val record = roster.self
                ?: return@mutate false
            val membership = roster.membership

            // The leaving seals the devices this one admitted, so only the authority key
            // may sign it; a device without one admitted no one.
            val sealed = membership.device.authority
            val privateKey = if (sealed != null) {
                checkNotNull(authority) {
                    "Leaving the group needs the master password."
                }
                authority.checkCurrent(membership)
                authority.seed
            } else {
                roster.signKey
            }
            val revocation = RosterSigner(crypto, membership.groupId).leave(
                roster = roster.validated,
                deviceId = record.id,
                at = roster.clock(now).now(),
                privateKey = privateKey,
            )
            val stored = roster.stored
            roster.store.saveRoster(stored.copy(revoked = stored.revoked + revocation))
            true
        }
        .bind()

    /** Hands the roster to the connected devices; unlink still succeeds if they are gone. */
    private suspend fun publishBestEffort(
        publication: KeyguardSessionPublication,
    ) {
        runCatchingNonFatal {
            publication.publish(PUBLISH_TIMEOUT_MS)
        }
    }
}
