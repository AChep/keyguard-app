package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DKeyguardDevice
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.provider.keyguard.sync.localRosterOrNull
import kotlin.time.Instant

private const val FINGERPRINT_LENGTH = 16

private const val FINGERPRINT_GROUP = 4

/**
 * Returns the devices of the roster of the group [groupId] whose signatures verify, or none
 * if there is no such group. This device shows the name of its membership, which a rename
 * also signs into its record, see [com.artemchep.keyguard.common.usecase.PutKeyguardDeviceLabel].
 */
internal fun Database.keyguardDevices(
    groupId: P2pGroupId,
    crypto: P2pCrypto,
): List<DKeyguardDevice> {
    val roster = localRosterOrNull(groupId, crypto)
        ?: return emptyList()
    val membership = roster.membership
    val self = membership.device.deviceId
    return roster.validated.devices
        .distinctBy { it.id }
        .map { record ->
            val isThisDevice = record.id == self
            DKeyguardDevice(
                id = record.id,
                label = if (isThisDevice) membership.device.label else record.label,
                platform = record.platform,
                fingerprint = keyguardDeviceFingerprint(crypto, record.noisePub),
                addedAt = Instant.fromEpochMilliseconds(record.addedAt.wall),
                addedBy = record.addedBy.takeIf { it != record.id },
                isThisDevice = isThisDevice,
                revoked = !roster.activity.isActive(record.id),
                removedByRemoved = roster.activity.wasRevokedByRevoked(record.id),
            )
        }
}

/**
 * Returns the first 16 hex digits of the SHA-256 of the Noise public key
 * [noisePub], in groups of four: `1a2b 3c4d 5e6f 7a8b`.
 */
internal fun keyguardDeviceFingerprint(
    crypto: P2pCrypto,
    noisePub: String,
): String {
    val key = P2pEncoding.base64UrlOrNull(noisePub)
        ?: noisePub.encodeToByteArray()
    return P2pEncoding.hex(crypto.sha256(key))
        .take(FINGERPRINT_LENGTH)
        .chunked(FINGERPRINT_GROUP)
        .joinToString(separator = " ")
}
