package com.artemchep.keyguard.provider.keyguard

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity

/** The size of a group key, in bytes. */
internal const val P2P_GROUP_KEY_SIZE = 32

/** Returns a new random 32-byte group key, base64url. */
internal fun newP2pGroupKey(
    cryptoGenerator: CryptoGenerator,
): String = P2pEncoding.base64Url(cryptoGenerator.seed(P2P_GROUP_KEY_SIZE))

/**
 * Returns the roster of a new group the [device] founds at [addedAt], admitted by its open
 * [authority] key. The id of the group derives from the founder's record, see
 * [com.artemchep.keyguard.feature.p2psync.roster.derivedGroupId].
 */
internal fun foundP2pGroup(
    device: P2pDevice,
    authority: KeyguardAuthorityKey,
    crypto: P2pCrypto,
    addedAt: Hlc,
): Roster {
    val founder = founderRecord(
        crypto = crypto,
        device = device,
        addedAt = addedAt,
        authority = authority,
    )
    return Roster(
        founder = device.deviceId,
        devices = listOf(founder),
    )
}

/** `true` if the roster admits the [device] with its own keys, and it is active. */
internal fun Roster.admits(device: P2pDevice, crypto: P2pCrypto): Boolean {
    val record = devices.firstOrNull { it.id == device.deviceId }
        ?: return false
    val sameKeys = record.noisePub == device.noisePublicKeyBase64 &&
        record.signPub == device.signPublicKeyBase64 &&
        record.authorityPub == device.authority?.publicKeyBase64.orEmpty()
    return sameKeys && RosterActivity(this, crypto::sha256).isActive(device.deviceId)
}
