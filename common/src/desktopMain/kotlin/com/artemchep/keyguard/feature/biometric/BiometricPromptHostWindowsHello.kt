package com.artemchep.keyguard.feature.biometric

import com.artemchep.autotype.biometricsUnwrapSecret
import com.artemchep.autotype.biometricsWrapSecret
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.platform.LeBiometricCipherWindowsHello
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * Wraps and unwraps the cipher secret with a Windows Hello
 * protected key. Windows Hello shows its own prompt while it
 * unwraps the secret.
 */
class BiometricPromptHostWindowsHello internal constructor(
    private val cryptoGenerator: CryptoGenerator,
    private val operations: WindowsHelloBiometricOperations,
) : BiometricPromptHost {
    constructor(cryptoGenerator: CryptoGenerator) : this(
        cryptoGenerator = cryptoGenerator,
        operations = NativeWindowsHelloBiometricOperations,
    )

    constructor(directDI: DirectDI) : this(
        cryptoGenerator = directDI.instance(),
    )

    override suspend fun createCipher(purpose: BiometricPurpose): LeBiometricCipher =
        when (purpose) {
            is BiometricPurpose.Encrypt -> cryptoGenerator.seed(length = 32)
                .useAndClear { secret ->
                    LeBiometricCipherWindowsHello.forEncryption(
                        secret = secret,
                        aesIv = cryptoGenerator.seed(length = 16),
                    )
                }

            is BiometricPurpose.Decrypt ->
                LeBiometricCipherWindowsHello.forDecryption(purpose.iv.byteArray)
        }

    override suspend fun materialize(
        request: BiometricPromptRequest,
        cipher: LeBiometricCipher,
    ) {
        val helloCipher = cipher as? LeBiometricCipherWindowsHello
            ?: error("Unsupported desktop biometric cipher.")
        if (helloCipher.forEncryption) {
            helloCipher.materializeForEncryption(request)
        } else {
            helloCipher.materializeForDecryption(request)
        }
    }

    private suspend fun LeBiometricCipherWindowsHello.materializeForEncryption(
        request: BiometricPromptRequest,
    ) = copySecretToWrap().useAndClear { secret ->
        try {
            operations.wrapSecret(
                windowHandle = request.windowHandle,
                title = request.title,
                secret = secret,
            ).useAndClear { wrappedSecret ->
                // Native wrapping immediately unwraps and validates the
                // secret before returning the persisted payload.
                completeEncryption(wrappedSecret)
            }
        } finally {
            clearSecretToWrap()
        }
    }

    private suspend fun LeBiometricCipherWindowsHello.materializeForDecryption(
        request: BiometricPromptRequest,
    ) = copyWrappedSecret().useAndClear { wrappedSecret ->
        operations.unwrapSecret(
            windowHandle = request.windowHandle,
            title = request.title,
            wrappedSecret = wrappedSecret,
        ).useAndClear { unwrappedSecret ->
            completeDecryption(unwrappedSecret)
        }
    }
}

internal interface WindowsHelloBiometricOperations {
    suspend fun wrapSecret(
        windowHandle: Long,
        title: String,
        secret: ByteArray,
    ): ByteArray

    suspend fun unwrapSecret(
        windowHandle: Long,
        title: String,
        wrappedSecret: ByteArray,
    ): ByteArray
}

private object NativeWindowsHelloBiometricOperations : WindowsHelloBiometricOperations {
    override suspend fun wrapSecret(
        windowHandle: Long,
        title: String,
        secret: ByteArray,
    ): ByteArray = biometricsWrapSecret(
        windowHandle = windowHandle,
        title = title,
        secret = secret,
    )

    override suspend fun unwrapSecret(
        windowHandle: Long,
        title: String,
        wrappedSecret: ByteArray,
    ): ByteArray = biometricsUnwrapSecret(
        windowHandle = windowHandle,
        title = title,
        wrappedSecret = wrappedSecret,
    )
}
