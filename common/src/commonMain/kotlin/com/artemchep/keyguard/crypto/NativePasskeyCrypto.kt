package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyError
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyInspectionResult
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyMaterial
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyProfile
import com.artemchep.keyguard.common.service.crypto.PasskeyPublicKey
import com.artemchep.keyguard.common.service.crypto.PasskeySignResult
import com.artemchep.keyguard.common.service.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativePasskeyAlgorithm
import com.artemchep.keyguard.nativecrypto.NativePasskeyKeyError
import com.artemchep.keyguard.nativecrypto.NativePasskeyKeyInspectionResult
import com.artemchep.keyguard.nativecrypto.NativePasskeyKeyMaterial
import com.artemchep.keyguard.nativecrypto.NativePasskeyKeyProfile
import com.artemchep.keyguard.nativecrypto.NativePasskeySignResult

object NativePasskeyCrypto : PasskeyCrypto {
    override val supportedAlgorithms: Set<PasskeySignatureAlgorithm> =
        setOf(PasskeySignatureAlgorithm.ES256)

    override fun generate(
        algorithm: PasskeySignatureAlgorithm,
    ): PasskeyKeyMaterial = NativeCrypto.passkeys
        .generate(algorithm.toNative())
        .toDomain()

    override fun inspect(
        privateKeyPkcs8: ByteArray,
    ): PasskeyKeyInspectionResult = when (
        val result = NativeCrypto.passkeys.inspect(privateKeyPkcs8)
    ) {
        is NativePasskeyKeyInspectionResult.Success ->
            PasskeyKeyInspectionResult.Success(result.keyMaterial.toDomain())

        is NativePasskeyKeyInspectionResult.Error ->
            PasskeyKeyInspectionResult.Error(result.reason.toDomain())
    }

    override fun sign(
        algorithm: PasskeySignatureAlgorithm,
        privateKeyPkcs8: ByteArray,
        data: ByteArray,
    ): PasskeySignResult = when (
        val result = NativeCrypto.passkeys.sign(
            algorithm = algorithm.toNative(),
            privateKeyPkcs8 = privateKeyPkcs8,
            data = data,
        )
    ) {
        is NativePasskeySignResult.Success ->
            PasskeySignResult.Success(result.signatureDer)

        is NativePasskeySignResult.Error ->
            PasskeySignResult.Error(result.reason.toDomain())
    }
}

private fun PasskeySignatureAlgorithm.toNative(): NativePasskeyAlgorithm = when (this) {
    PasskeySignatureAlgorithm.ES256 -> NativePasskeyAlgorithm.ES256
}

private fun NativePasskeyKeyMaterial.toDomain(): PasskeyKeyMaterial {
    val domainProfile = when (profile) {
        NativePasskeyKeyProfile.EC_P256 -> PasskeyKeyProfile.EC_P256
    }
    return PasskeyKeyMaterial(
        profile = domainProfile,
        privateKeyPkcs8 = privateKeyPkcs8,
        publicKey = PasskeyPublicKey.EcP256(
            x = publicKeyX,
            y = publicKeyY,
            spki = publicKeySpki,
        ),
    )
}

private fun NativePasskeyKeyError.toDomain(): PasskeyKeyError = when (this) {
    NativePasskeyKeyError.MALFORMED -> PasskeyKeyError.MALFORMED
    NativePasskeyKeyError.UNSUPPORTED -> PasskeyKeyError.UNSUPPORTED
    NativePasskeyKeyError.RESOURCE_LIMIT -> PasskeyKeyError.RESOURCE_LIMIT
}
