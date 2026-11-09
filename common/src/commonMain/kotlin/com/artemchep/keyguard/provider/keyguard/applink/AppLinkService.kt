package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentBundle
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.roster.derivedGroupId
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKey
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.KeyguardDeviceFactory
import com.artemchep.keyguard.provider.keyguard.admits
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingError
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingException
import com.artemchep.keyguard.provider.keyguard.foundP2pGroup
import com.artemchep.keyguard.provider.keyguard.newP2pGroupKey
import com.artemchep.keyguard.provider.keyguard.observeP2pGroup
import com.artemchep.keyguard.provider.keyguard.p2pMembershipOrNull
import com.artemchep.keyguard.provider.keyguard.saveP2pMembership
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import com.artemchep.keyguard.provider.keyguard.sync.localRosterOrNull
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The live session of linked apps: this device's [membership] in the link. A new membership
 * starts the session over, see [AppLinkWorker].
 */
data class AppLinkLive(
    val membership: P2pMembership,
)

/**
 * Links this app with the apps on the user's other devices: founds the link, joins the
 * link of another app, and leaves it. The link lives in the vault database, so it syncs
 * only while the vault is unlocked.
 *
 * An app is in at most one link. The link is founded once the user starts linking a
 * device; until another device joins it, the app is [DAppLinkState.Alone] and may still
 * join the link of another app instead.
 */
// A holder of what linking needs.
@Suppress("LongParameterList", "TooManyFunctions")
class AppLinkService internal constructor(
    private val db: VaultDatabaseManager,
    private val crypto: P2pCrypto,
    private val cryptoGenerator: CryptoGenerator,
    private val deviceFactory: KeyguardDeviceFactory,
    private val authorityKeys: KeyguardAuthorityKeys,
    private val categories: AppSyncCategories,
    private val switches: AppSyncSwitches,
    private val deviceSettings: AppLinkDeviceSettings,
    private val getPurchased: GetPurchased,
    private val now: () -> Instant = { Clock.System.now() },
) {
    companion object {
        private const val TAG = "AppLink"
    }

    /** Emits whether this app is linked, and to how many devices. */
    fun observeState(): Flow<DAppLinkState> = db.observeP2pGroup(P2pGroupId.AppLink, ::stateOf)

    /**
     * Emits the live session while this app is an active member, even when alone: a
     * removed peer may still need its notice. A session without peers opens no rooms.
     */
    fun observeLive(): Flow<AppLinkLive?> = db.observeP2pGroup(P2pGroupId.AppLink) { database ->
        database.appLinkMembershipOrNull()
            ?.takeUnless { stateOf(database) is DAppLinkState.Removed }
            ?.let(::AppLinkLive)
    }

    /** Returns the membership of this app in its link, or `null` if it is not linked. */
    suspend fun membership(): P2pMembership? = db.get()
        .bind()
        .appLinkMembershipOrNull()

    /** Returns `true` if this app is linked to other devices, see [DAppLinkState.Linked]. */
    suspend fun isLinked(): Boolean = stateOf(db.get().bind()) is DAppLinkState.Linked

    /**
     * Returns `true` if the device of the Noise key [noisePub] is an active device of the
     * link of this app.
     */
    suspend fun isLinkedWith(noisePub: ByteArray): Boolean {
        val roster = db.get().bind().localRosterOrNull(P2pGroupId.AppLink, crypto)
            ?: return false
        val key = P2pEncoding.base64Url(noisePub)
        return roster.validated.devices.any { device ->
            device.noisePub == key && roster.activity.isActive(device.id)
        }
    }

    /**
     * Gets this app ready to link another one: founds the link as the device named
     * [deviceLabel], with the [network] settings, unless this app is linked already. The
     * authority key of the new device is sealed under the master [password]. The id of the
     * link derives from the record of its founder, see [derivedGroupId].
     *
     * @return the open authority key of the new link, which the caller closes, or `null`
     *   if this app was linked already. Opening it again would derive its sealing key twice.
     * @throws KeyguardPairingException if linking needs Premium.
     */
    suspend fun prepareLink(
        deviceLabel: String,
        network: P2pNetwork,
        password: String,
    ): KeyguardAuthorityKey? {
        val database = db.get().bind()
        val state = stateOf(database)
        val effectiveNetwork = if (state is DAppLinkState.Linked) {
            checkNotNull(database.appLinkMembershipOrNull()).network
        } else {
            network
        }
        requireCanLink(effectiveNetwork)
        // A link with other devices stays as it is; a lone one, or one this
        // device was removed from, starts over with the new settings.
        if (state is DAppLinkState.Linked) return null

        val authority = authorityKeys.create(password).bind()
        var prepared = false
        try {
            val device = deviceFactory.create(label = deviceLabel, authority = authority.authority)
                .bind()
            val groupKey = newP2pGroupKey(cryptoGenerator)
            db.mutate(TAG) { database ->
                // The first stamp of the clock of the new group.
                val addedAt = Hlc(wall = now().toEpochMilliseconds(), counter = 0, node = device.deviceId)
                // Signed before the transaction: it reads and writes nothing.
                val roster = foundP2pGroup(device, authority, crypto, addedAt)
                val membership = P2pMembership(
                    groupId = roster.devices.single().derivedGroupId(crypto::sha256),
                    groupKeyBase64 = groupKey,
                    founderId = device.deviceId,
                    device = device,
                    network = network,
                )
                // One commit for the new link and every record it publishes.
                database.transaction {
                    database.p2pGroupQueries.deleteByGroupId(groupId = P2pGroupId.APP_LINK_ID)
                    database.saveAppLinkMembership(membership)
                    val store = KeyguardSyncStore(database, P2pGroupId.APP_LINK_ID)
                    store.saveClock(addedAt)
                    store.saveRoster(roster)
                    newPass(database, membership, enabled = emptySet())
                        .publishUnrecorded()
                }
            }.bind()
            prepared = true
        } finally {
            if (!prepared) authority.close()
        }
        return authority
    }

    /**
     * Checks that this app may join the link of another one.
     *
     * @throws KeyguardPairingException if joining needs Premium, or this app is linked to
     * other devices already.
     */
    suspend fun checkJoinable() {
        requireCanLink(membership()?.network ?: deviceSettings.network().first())
        val state = db.get().bind().let(::stateOf)
        if (state is DAppLinkState.Linked) throw alreadyLinked()
    }

    /**
     * Joins the link of the [bundle], which admits the [device], with the [network] settings.
     * The id of the link must derive from the record of its founder, see [derivedGroupId].
     */
    suspend fun import(
        bundle: EnrollmentBundle,
        device: P2pDevice,
        network: P2pNetwork,
    ) {
        val adoptedNetwork = network.copy(
            relayUrl = network.relayUrl ?: bundle.relay,
            iceUrl = network.iceUrl ?: bundle.ice,
        )
        requireCanLink(adoptedNetwork)
        val groupId = bundle.group
        val founded = bundle.roster.devices.any { record ->
            record.id == bundle.roster.founder && record.derivedGroupId(crypto::sha256) == groupId
        }
        require(founded) {
            "The id of the link does not derive from its founder."
        }
        val roster = RosterValidator(crypto, groupId).validate(bundle.roster)
        require(roster.admits(device, crypto)) {
            "The link does not admit this device."
        }
        // The member that admitted this device sent the bundle.
        val member = roster.devices.first { it.id == device.deviceId }.addedBy
        val endpoint = KeyguardRelayDefaults.endpointOfCode(
            relayUrl = adoptedNetwork.relayUrl,
            iceUrl = adoptedNetwork.iceUrl,
            network = network,
        )
        val membership = P2pMembership(
            groupId = groupId,
            groupKeyBase64 = bundle.groupKey,
            founderId = roster.founder,
            device = device,
            network = adoptedNetwork.copy(
                accessToken = endpoint.token,
            ),
        )
        val enabled = switches.enabledNow()
        db.mutate(TAG) { database ->
            // One commit for the joined link and every record it writes.
            database.transaction {
                // Checked again: a link may have formed since the pairing started.
                if (stateOf(database) is DAppLinkState.Linked) throw alreadyLinked()

                database.p2pGroupQueries.deleteByGroupId(groupId = P2pGroupId.APP_LINK_ID)
                database.saveAppLinkMembership(membership)
                KeyguardSyncStore(database, P2pGroupId.APP_LINK_ID).saveRoster(roster)
                val pass = newPass(database, membership, enabled)
                pass.applyEnvelope(member, Envelope(entries = bundle.entries), materialize = false)
                // First: a record this app has may be one the link has too.
                pass.publishUnrecorded()
                pass.materializeAll()
            }
        }.bind()
    }

    /**
     * Takes this app out of its link. The records stay on this device, and so do its name
     * and network settings, for the next link.
     */
    suspend fun dissolve() {
        membership()
            ?.let { deviceSettings.keep(it).bind() }
        db.mutate(TAG) { database ->
            database.p2pGroupQueries.deleteByGroupId(groupId = P2pGroupId.APP_LINK_ID)
        }.bind()
    }

    private fun newPass(
        database: Database,
        membership: P2pMembership,
        enabled: Set<DAppLinkSyncCategory>,
    ) = AppLinkSyncPass(
        database = database,
        membership = membership,
        crypto = crypto,
        categories = categories,
        enabled = enabled,
        now = now,
    )

    private fun stateOf(database: Database): DAppLinkState {
        val roster = database.localRosterOrNull(P2pGroupId.AppLink, crypto)
            ?: return DAppLinkState.NotLinked
        // This device is removed once its record is revoked, or gone.
        val self = roster.self
        val peers = roster.activity.activeIds.count { it != self?.id }
        return when {
            self == null -> DAppLinkState.Removed
            peers == 0 -> DAppLinkState.Alone
            else -> DAppLinkState.Linked(peers)
        }
    }

    /** Checks the relay actually used by an operation, including a scanned pairing code. */
    internal suspend fun requireCanLink(network: P2pNetwork) {
        if (!canLinkApps(getPurchased().first(), network)) {
            throw KeyguardPairingException(
                error = KeyguardPairingError.FAILED,
                message = "Linking apps requires a Premium purchase or a self-hosted relay server.",
            )
        }
    }

    private fun alreadyLinked() = KeyguardPairingException(
        error = KeyguardPairingError.ALREADY_LINKED,
        message = "This app is linked to other devices already.",
    )
}

/** Returns the membership of the group of linked apps, or `null` if this app is not linked. */
internal fun Database.appLinkMembershipOrNull(): P2pMembership? =
    p2pMembershipOrNull(P2pGroupId.AppLink)

/**
 * Saves the membership of the group of linked apps, creating the group if there is none.
 * Never replaces the row: that would delete everything the group owns.
 */
internal fun Database.saveAppLinkMembership(
    membership: P2pMembership,
) = saveP2pMembership(P2pGroupId.AppLink, SyncScope.APP, membership)
