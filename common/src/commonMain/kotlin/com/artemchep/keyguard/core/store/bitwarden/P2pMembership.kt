package com.artemchep.keyguard.core.store.bitwarden

import kotlinx.serialization.Serializable

/**
 * The membership in a device-to-device sync group: the group, this device in it, and how
 * the device reaches the other ones. Stored as JSON, see `p2pGroup.membership`.
 *
 * Keys are base64url without padding, the encoding of the sync roster.
 *
 * @property groupId the id of the sync group.
 * @property groupKeyBase64 the 32-byte group key.
 * @property founderId the id of the device that founded the group.
 */
@Serializable
data class P2pMembership(
    val groupId: String,
    val groupKeyBase64: String,
    val founderId: String,
    val device: P2pDevice,
    val network: P2pNetwork = P2pNetwork(),
) {
    override fun toString(): String = "P2pMembership(" +
        "groupId=$groupId, " +
        "founderId=$founderId, " +
        "device=$device, " +
        "network=$network" +
        ")"
}

/**
 * This device in a device-to-device sync group: its X25519 Noise key, its ML-KEM-1024 key
 * that the items other devices leave for it on the relay are sealed to, and its Ed25519
 * device key.
 *
 * @property authority the authority key that admits and removes other devices, or `null`
 *   for a device that can not.
 */
@Serializable
data class P2pDevice(
    val deviceId: String,
    val label: String,
    val platform: String,
    val noisePublicKeyBase64: String,
    val noisePrivateKeyBase64: String,
    val kemPublicKeyBase64: String,
    val kemPrivateKeyBase64: String,
    val signPublicKeyBase64: String,
    val signPrivateKeyBase64: String,
    val authority: P2pAuthority? = null,
) {
    override fun toString(): String = "P2pDevice(deviceId=$deviceId, label=$label, platform=$platform)"
}

/**
 * The authority key of a device: the Ed25519 key that admits and removes the other devices
 * of its sync group.
 *
 * Its 32-byte seed is sealed under a key derived from the master password alone, with a salt
 * of its own: neither the master key nor another unlock factor opens it. So a stolen device,
 * even unlocked, can not change who is in the group.
 *
 * @property publicKeyBase64 the base64url Ed25519 public key.
 * @property saltBase64 the base64url salt the sealing key is derived with.
 * @property kdfVersion the version of the password key derivation, see
 *   [com.artemchep.keyguard.common.model.MasterKdfVersion].
 * @property sealedSeed the seed, sealed with AES-256-CBC and HMAC-SHA256.
 */
@Serializable
data class P2pAuthority(
    val publicKeyBase64: String,
    val saltBase64: String,
    val kdfVersion: Int,
    val sealedSeed: String,
) {
    override fun toString(): String = "P2pAuthority(publicKeyBase64=$publicKeyBase64)"
}

/**
 * How the device reaches the other devices of its sync group.
 *
 * @property relayUrl the relay, or `null` for the default one.
 * @property iceUrl the ICE servers endpoint, or `null` for the default one.
 * @property accessToken the token of a private relay.
 * @property allowLan `true` to connect directly on the local network.
 * @property relayStorage `true` to let the relay keep the state of the group for the
 *   devices that are offline. The group uses it only once every active device allows it:
 *   the device lists it in its roster record, see
 *   [com.artemchep.keyguard.feature.p2psync.model.DeviceFeature.RELAY_STORAGE].
 */
@Serializable
data class P2pNetwork(
    val relayUrl: String? = null,
    val iceUrl: String? = null,
    val accessToken: String? = null,
    val allowLan: Boolean = true,
    val relayStorage: Boolean = true,
) {
    override fun toString(): String =
        "P2pNetwork(relayUrl=$relayUrl, iceUrl=$iceUrl, allowLan=$allowLan, relayStorage=$relayStorage)"
}

/** Returns the settings with the values trimmed, and the blank ones as `null`: the defaults. */
fun P2pNetwork.normalized(): P2pNetwork = copy(
    relayUrl = relayUrl?.trim()?.ifEmpty { null },
    iceUrl = iceUrl?.trim()?.ifEmpty { null },
    accessToken = accessToken?.trim()?.ifEmpty { null },
)
