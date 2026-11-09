package com.artemchep.keyguard.provider.keyguard

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.core.store.bitwarden.P2pAuthority
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.DeviceKeys
import com.artemchep.keyguard.feature.p2psync.model.DeviceRecordRules
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.roster.derivedGroupId
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeEd25519KeyPair
import com.artemchep.keyguard.nativecrypto.NativeMlKemKeyPair
import com.artemchep.keyguard.nativecrypto.NativeNoiseKeyPair
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Creates the identity of this device in a sync group: a fresh device id,
 * a Noise static key pair, an ML-KEM-1024 key pair, an Ed25519 device key
 * pair, and its authority key, see [KeyguardAuthorityKeys].
 */
class KeyguardDeviceFactory(
    private val cryptoGenerator: CryptoGenerator,
    private val generateNoiseKeyPair: () -> NativeNoiseKeyPair = NativeCryptoNoise::generateKeyPair,
    private val generateKemKeyPair: () -> NativeMlKemKeyPair = NativeCryptoPrimitives::mlKemGenerate,
    private val generateSignKeyPair: () -> NativeEd25519KeyPair = NativeCryptoPrimitives::ed25519Generate,
) {
    /**
     * Creates a new device named [label] that holds the [authority] key. The name must be
     * one every device takes, see [DeviceRecordRules.isValidLabel]: its record carries it.
     */
    fun create(
        label: String,
        authority: P2pAuthority,
    ): IO<P2pDevice> = ioEffect(Dispatchers.IO) {
        require(DeviceRecordRules.isValidLabel(label)) {
            "The name of the device is not valid."
        }
        val noiseKeyPair = generateNoiseKeyPair()
        val kemKeyPair = generateKemKeyPair()
        val signKeyPair = generateSignKeyPair()
        P2pDevice(
            deviceId = cryptoGenerator.uuid(),
            label = label,
            platform = CurrentPlatform.keyguardPlatformName(),
            noisePublicKeyBase64 = P2pEncoding.base64Url(noiseKeyPair.publicKey),
            noisePrivateKeyBase64 = P2pEncoding.base64Url(noiseKeyPair.privateKey),
            kemPublicKeyBase64 = P2pEncoding.base64Url(kemKeyPair.publicKey),
            kemPrivateKeyBase64 = P2pEncoding.base64Url(kemKeyPair.privateKey),
            signPublicKeyBase64 = P2pEncoding.base64Url(signKeyPair.publicKey),
            signPrivateKeyBase64 = P2pEncoding.base64Url(signKeyPair.privateKey),
            authority = authority,
        )
    }
}

/**
 * Returns the new, unsigned roster record of the [device], admitted by
 * [addedBy] at [addedAt], with the [caps], by default what this build
 * supports, see [DeviceCaps.CURRENT]. Pass the device's own id as
 * [addedBy] for the founder of a group, which admits itself. A rename
 * later signs a newer copy, see [RosterSigner.update].
 */
fun P2pDevice.toRosterDevice(
    addedAt: Hlc,
    addedBy: String = deviceId,
    caps: DeviceCaps = DeviceCaps.CURRENT,
): RosterDevice = RosterDevice(
    id = deviceId,
    label = label,
    platform = platform,
    noisePub = noisePublicKeyBase64,
    keys = DeviceKeys.of(mlKem1024 = kemPublicKeyBase64),
    signPub = signPublicKeyBase64,
    authorityPub = authority?.publicKeyBase64.orEmpty(),
    addedAt = addedAt,
    addedBy = addedBy,
    updatedAt = addedAt,
    caps = caps,
)

/**
 * Returns the Ed25519 private key the device signs its roster records with.
 *
 * @throws IllegalArgumentException if it is malformed.
 */
fun P2pDevice.signPrivateKey(): ByteArray = requireNotNull(P2pEncoding.base64UrlOrNull(signPrivateKeyBase64)) {
    "The signing key of the device is malformed."
}

/**
 * Returns the Noise static private key of the device.
 *
 * @throws IllegalArgumentException if it is malformed.
 */
fun P2pDevice.noisePrivateKey(): ByteArray = requireNotNull(P2pEncoding.base64UrlOrNull(noisePrivateKeyBase64)) {
    "The Noise key of the device is malformed."
}

/**
 * Returns the founder record of a new group: the [device] with the [caps],
 * signed by its device key and admitted by its own open [authority] key, both
 * for the group whose id the record derives, see [derivedGroupId].
 */
fun founderRecord(
    crypto: P2pCrypto,
    device: P2pDevice,
    addedAt: Hlc,
    authority: KeyguardAuthorityKey,
    caps: DeviceCaps = DeviceCaps.CURRENT,
): RosterDevice {
    require(device.authority?.publicKeyBase64 == authority.authority.publicKeyBase64) {
        "The authority key is not the one of the device."
    }
    val record = device.toRosterDevice(addedAt = addedAt, caps = caps)
    val signer = RosterSigner(crypto, record.derivedGroupId(crypto::sha256))
    return signer.admit(signer.selfSign(record, device.signPrivateKey()), authority.seed)
}

/** The platform name a device record carries, for display only. */
fun Platform.keyguardPlatformName(): String = when (this) {
    is Platform.Mobile.Android -> if (isWatch) "wear" else "android"
    is Platform.Mobile.Ios -> "ios"
    is Platform.Desktop.Linux -> "linux"
    is Platform.Desktop.Windows -> "windows"
    is Platform.Desktop.MacOS -> "macos"
    is Platform.Desktop.Other -> "desktop"
}
