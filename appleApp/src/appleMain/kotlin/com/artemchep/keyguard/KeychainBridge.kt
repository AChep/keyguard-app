package com.artemchep.keyguard

import kotlin.concurrent.Volatile
import platform.LocalAuthentication.LAContext

class KeychainBiometricResult(val value: String?, val status: Int)

interface KeychainBridge {
    fun setBiometric(account: String, value: String, context: LAContext): KeychainBiometricResult

    fun getBiometric(account: String, context: LAContext): KeychainBiometricResult

    /** Stores [value] for [account], replacing any existing item. Returns success. */
    fun set(account: String, value: String): Boolean

    /** Reads the value for [account], or null when absent. */
    fun get(account: String): String?

    /** Deletes [account]; returns true if it was removed or already absent. */
    fun delete(account: String): Boolean

    fun contains(account: String): Boolean
}

object KeychainBridgeRegistry {
    @Volatile
    var bridge: KeychainBridge? = null
}

/** Entry point for the Swift `SecItem` implementation of [KeychainBridge]. */
fun registerKeychainBridge(
    bridge: KeychainBridge,
) {
    KeychainBridgeRegistry.bridge = bridge
}
