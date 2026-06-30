package com.artemchep.keyguard.crypto

import kotlin.concurrent.Volatile

/**
 * Bridge for generating Ed25519 key pairs on Apple platforms.
 *
 * Apple's Security framework cannot generate Ed25519 keys, and CryptoKit
 * (`Curve25519.Signing.PrivateKey`) is Swift-only and unreachable from
 * Kotlin/Native cinterop. The app layer (iOS/macOS) therefore implements this
 * interface in Swift using CryptoKit and installs it via
 * [AppleEd25519BridgeRegistry] at startup.
 */
interface AppleEd25519Bridge {
    fun generate(): AppleEd25519KeyMaterial
}

/**
 * Raw Ed25519 key material as produced by CryptoKit:
 *  - [seed]: the 32-byte private seed (`rawRepresentation`),
 *  - [publicKey]: the 32-byte public key (`publicKey.rawRepresentation`).
 */
class AppleEd25519KeyMaterial(
    val seed: ByteArray,
    val publicKey: ByteArray,
)

object AppleEd25519BridgeRegistry {
    @Volatile
    var bridge: AppleEd25519Bridge? = null
}
