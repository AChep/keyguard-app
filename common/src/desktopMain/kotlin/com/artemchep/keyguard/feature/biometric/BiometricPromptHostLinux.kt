package com.artemchep.keyguard.feature.biometric

import com.artemchep.autotype.biometricsPrepareEnrollment
import com.artemchep.autotype.biometricsUnwrapSecret
import com.artemchep.autotype.biometricsVerify
import com.artemchep.autotype.biometricsWrapSecret
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.platform.LeBiometricCipherLinux
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class BiometricPromptHostLinux internal constructor(
    private val operations: LinuxBiometricOperations,
) : BiometricPromptHost {
    constructor() : this(NativeLinuxBiometricOperations)

    override suspend fun createCipher(purpose: BiometricPurpose): LeBiometricCipher =
        when (purpose) {
            is BiometricPurpose.Encrypt -> LeBiometricCipherLinux.forEncryption { secret ->
                ioEffect { operations.protect(secret) }.bindBlocking()
            }
            is BiometricPurpose.Decrypt -> LeBiometricCipherLinux.forDecryption(purpose.iv.byteArray)
        }

    override suspend fun materialize(request: BiometricPromptRequest, cipher: LeBiometricCipher) {
        val linuxCipher = cipher as? LeBiometricCipherLinux
            ?: error("Unsupported desktop biometric cipher.")
        // The cipher instance is shared by every prompt of this unlock screen,
        // so do not clear it here: a plaintext a previous prompt released may
        // still be waiting for its unlock job. A completed decryption replaces
        // it and a failed prompt clears it.
        if (linuxCipher.forEncryption) {
            // Explicit enrollment installs the polkit policy, which may ask
            // for administrator approval, and then requires confirmation.
            // Password rearming calls encode directly and must not prompt.
            operations.prepareEnrollment()
            operations.verify(request)
        } else {
            operations.release(request, linuxCipher.iv).useAndClear { secret ->
                currentCoroutineContext().ensureActive()
                linuxCipher.completeDecryption(secret)
            }
        }
    }
}

internal interface LinuxBiometricOperations {
    suspend fun prepareEnrollment()
    suspend fun verify(request: BiometricPromptRequest)
    suspend fun protect(secret: ByteArray): ByteArray
    suspend fun release(request: BiometricPromptRequest, handle: ByteArray): ByteArray
}

private object NativeLinuxBiometricOperations : LinuxBiometricOperations {
    override suspend fun prepareEnrollment() = biometricsPrepareEnrollment()

    override suspend fun verify(request: BiometricPromptRequest) = biometricsVerify(
        windowHandle = request.windowHandle,
        title = request.title,
    )

    override suspend fun protect(secret: ByteArray): ByteArray = biometricsWrapSecret(
        windowHandle = 0L,
        title = "",
        secret = secret,
    )

    override suspend fun release(request: BiometricPromptRequest, handle: ByteArray): ByteArray =
        biometricsUnwrapSecret(
            windowHandle = request.windowHandle,
            title = request.title,
            wrappedSecret = handle,
        )
}
