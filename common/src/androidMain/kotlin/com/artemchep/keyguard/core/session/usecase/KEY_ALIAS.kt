package com.artemchep.keyguard.core.session.usecase

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.platform.LeBiometricCipherJvm
import kotlinx.coroutines.flow.MutableStateFlow
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

private const val KEY_ALIAS = "biometrics"

class BiometricStatusUseCaseImpl(
    private val application: Application,
) : BiometricStatusUseCase {
    constructor(directDI: DirectDI) : this(
        application = directDI.instance(),
    )

    override fun invoke() = kotlin.run {
        val state = kotlin.run {
            val hasStrongBiometric = hasStrongBiometric()
            if (hasStrongBiometric) {
                BiometricStatus.Available(
                    createCipher = ::createCipher,
                    deleteCipher = ::deleteCipher,
                )
            } else {
                BiometricStatus.Unavailable
            }
        }
        MutableStateFlow(state)
    }

    private fun hasStrongBiometric(): Boolean {
        val biometricManager = BiometricManager.from(application)
        return biometricManager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }
}

private fun getSecretKeyStore(): KeyStore {
    val keyStore = KeyStore.getInstance("AndroidKeyStore")
    // Before the keystore can be accessed,
    // it must be loaded...
    keyStore.load(null)
    return keyStore
}

private fun getSecretKey(): SecretKey {
    val keyStore = getSecretKeyStore()
    // ...if the key already exists, then there's no need
    // to regenerate that.
    val existingKey = keyStore.getKey(KEY_ALIAS, null)
    if (existingKey != null) {
        return existingKey as SecretKey
    }

    val keyPurpose = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    val keySpec = KeyGenParameterSpec.Builder(KEY_ALIAS, keyPurpose)
        .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setUserAuthenticationRequired(true)
        .build()

    val keyGenerator = KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    keyGenerator.init(keySpec)
    return keyGenerator.generateKey() as SecretKey
}

private fun deleteCipher() {
    val keyStore = getSecretKeyStore()
    keyStore.deleteEntry(KEY_ALIAS)
}

private fun createCipher(
    biometricPurpose: BiometricPurpose,
): LeBiometricCipher = createEmptyCipher().apply {
    val key = getSecretKey()
    when (biometricPurpose) {
        // Init cipher in encrypt mode with random iv
        // seed. The user should persist iv for future use.
        is BiometricPurpose.Encrypt -> init(Cipher.ENCRYPT_MODE, key)
        is BiometricPurpose.Decrypt -> {
            val spec = IvParameterSpec(biometricPurpose.iv.byteArray)
            init(Cipher.DECRYPT_MODE, key, spec)
        }
    }
}.let { platformCipher ->
    LeBiometricCipherJvm(platformCipher)
}

private fun createEmptyCipher(): Cipher = Cipher
    .getInstance(
        KeyProperties.KEY_ALGORITHM_AES + "/" +
                KeyProperties.BLOCK_MODE_CBC + "/" +
                KeyProperties.ENCRYPTION_PADDING_NONE,
    )
