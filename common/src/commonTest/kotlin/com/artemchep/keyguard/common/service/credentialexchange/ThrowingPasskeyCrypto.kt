package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.service.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyInspectionResult
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyMaterial
import com.artemchep.keyguard.common.service.crypto.PasskeySignResult
import com.artemchep.keyguard.common.service.crypto.PasskeySignatureAlgorithm

/**
 * A [PasskeyCrypto] whose every entry point raises, standing in for the real
 * seam when the native backend cannot initialize: `NativeCrypto` throws out of
 * `inspect` rather than returning [PasskeyKeyInspectionResult.Error].
 *
 * A resilience fixture, the mirror of `FakeSshKeyPkcs8Exporter(error = ...)`:
 * reaching it must produce a counted passkey skip, never lose the account.
 */
class ThrowingPasskeyCrypto(
    private val error: Throwable = IllegalStateException("the passkey backend is unavailable"),
) : PasskeyCrypto {
    override val supportedAlgorithms: Set<PasskeySignatureAlgorithm> = emptySet()

    override fun generate(
        algorithm: PasskeySignatureAlgorithm,
    ): PasskeyKeyMaterial = throw error

    override fun inspect(
        privateKeyPkcs8: ByteArray,
    ): PasskeyKeyInspectionResult = throw error

    override fun sign(
        algorithm: PasskeySignatureAlgorithm,
        privateKeyPkcs8: ByteArray,
        data: ByteArray,
    ): PasskeySignResult = throw error
}
