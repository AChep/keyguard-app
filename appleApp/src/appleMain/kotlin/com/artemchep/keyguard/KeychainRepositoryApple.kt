package com.artemchep.keyguard

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.platform.AppleBiometricKeychain
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import platform.LocalAuthentication.LAContext
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled

internal class KeychainRepositoryApple(
    private val errorMessage: suspend () -> String = { getString(Res.string.unlock_biometric_key_unavailable) },
) : KeychainRepository, AppleBiometricKeychain {
    override suspend fun putBiometric(id: String, value: String, context: LAContext) {
        withContext(Dispatchers.Default) {
            checkBiometricResult(bridge.setBiometric(id, value, context))
        }
    }

    override suspend fun getBiometric(id: String, context: LAContext): String =
        withContext(Dispatchers.Default) {
            val result = bridge.getBiometric(id, context)
            checkBiometricResult(result)
            result.value ?: throw biometricError(BiometricAuthException.ERROR_KEY_INVALIDATED)
        }

    private suspend fun checkBiometricResult(result: KeychainBiometricResult) {
        val code = when (result.status) {
            errSecSuccess -> return
            errSecItemNotFound -> BiometricAuthException.ERROR_KEY_INVALIDATED
            errSecUserCanceled -> BiometricAuthException.ERROR_USER_CANCELED
            errSecInteractionNotAllowed -> BiometricAuthException.ERROR_HW_UNAVAILABLE
            else -> BiometricAuthException.ERROR_UNKNOWN
        }
        throw biometricError(code)
    }

    private suspend fun biometricError(code: Int) = BiometricAuthException(code, errorMessage())

    private val bridge: KeychainBridge
        get() = KeychainBridgeRegistry.bridge
            ?: error("Keychain bridge is not registered.")

    override fun put(
        id: String,
        password: String,
        requireUserPresence: Boolean,
    ): IO<Unit> = ioEffect {
        require(!requireUserPresence) { "Use the authenticated biometric key API for protected items." }
        val ok = bridge.set(account = id, value = password)
        check(ok) { "Failed to store keychain item '$id'." }
    }

    override fun get(id: String): IO<String> = ioEffect {
        bridge.get(account = id)
            ?: throw NoSuchElementException("Keychain item '$id' not found.")
    }

    override fun delete(id: String): IO<Boolean> = ioEffect {
        bridge.delete(account = id)
    }

    override fun contains(id: String): IO<Boolean> = ioEffect {
        bridge.contains(account = id)
    }
}
