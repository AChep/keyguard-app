package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyAttestation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WebAuthnAuthenticatorDataProtocolTest {
    @Test
    fun `authenticator data flags use WebAuthn bits`() {
        assertEquals(
            0x01,
            flags(userPresence = true),
        )
        assertEquals(
            0x04,
            flags(userVerification = true),
        )
        assertEquals(
            0x08,
            flags(backupEligibility = true),
        )
        assertEquals(
            0x10,
            flags(backupState = true),
        )
        assertEquals(
            0x40,
            flags(attestationData = true),
        )
        assertEquals(
            0x80,
            flags(extensionData = true),
        )
    }

    // Spec coverage: Section 6.1 packs authenticator-data flags into one byte,
    // so independent AT and ED bits must combine without shifting each other.
    @Test
    fun `authenticator data flags combine attestation and extension bits`() {
        assertEquals(
            0xc0,
            flags(
                extensionData = true,
                attestationData = true,
            ),
        )
    }

    // Spec coverage: Section 6.5.1 Attested Credential Data caps credential ID
    // length at 1023 bytes; Section 7.1 repeats that RP verification limit.
    @Test
    fun `authenticator data rejects credential id above WebAuthn maximum`() {
        val factory = createAuthenticatorDataFactory()

        val error = assertFailsWith<IllegalArgumentException> {
            factory.encodeAuthenticatorData(
                rpId = "example.com",
                signCount = 0,
                credentialId = ByteArray(1024),
                credentialPublicKey = byteArrayOf(0xa0.toByte()),
                userVerified = true,
                userPresent = true,
            )
        }

        assertEquals(
            "WebAuthn credential ID must be at most 1023 bytes.",
            error.message,
        )
    }

    // Spec coverage: Section 8.7 None Attestation replaces the attestation
    // statement with fmt "none" and an empty attStmt; Section 6.5.1 still keeps
    // the authenticator AAGUID inside attestedCredentialData.
    @Test
    fun `none and omitted attestation preserve authenticator aaguid`() {
        val factory = createAuthenticatorDataFactory()

        val directAaguid = aaguidFor(
            factory = factory,
            attestation = CreatePasskeyAttestation.DIRECT,
        )
        val noneAaguid = aaguidFor(
            factory = factory,
            attestation = CreatePasskeyAttestation.NONE,
        )
        val omittedAaguid = aaguidFor(
            factory = factory,
            attestation = null,
        )

        assertFalse(directAaguid.all { it == 0.toByte() })
        assertContentEquals(directAaguid, noneAaguid)
        assertContentEquals(directAaguid, omittedAaguid)
    }

    @Test
    fun `indirect attestation derives anonymized aaguid from direct aaguid and credential id`() {
        val factory = createAuthenticatorDataFactory()
        val credentialId = byteArrayOf(0x10, 0x20, 0x30, 0x40)

        val directAaguid = aaguidFor(
            factory = factory,
            attestation = CreatePasskeyAttestation.DIRECT,
            credentialId = credentialId,
        )
        val indirectAaguid = aaguidFor(
            factory = factory,
            attestation = CreatePasskeyAttestation.INDIRECT,
            credentialId = credentialId,
        )
        val expectedIndirectAaguid = NativeCrypto.primitives.md5(directAaguid + credentialId)

        assertContentEquals(expectedIndirectAaguid, indirectAaguid)
        assertFalse(directAaguid.contentEquals(indirectAaguid))
    }

    private fun flags(
        extensionData: Boolean = false,
        attestationData: Boolean = false,
        backupState: Boolean = false,
        backupEligibility: Boolean = false,
        userVerification: Boolean = false,
        userPresence: Boolean = false,
    ): Int = authDataFlags(
        extensionData = extensionData,
        attestationData = attestationData,
        backupState = backupState,
        backupEligibility = backupEligibility,
        userVerification = userVerification,
        userPresence = userPresence,
    ).toInt() and 0xff

    private fun aaguidFor(
        factory: WebAuthnAuthenticatorDataFactory,
        attestation: CreatePasskeyAttestation?,
        credentialId: ByteArray = byteArrayOf(0x01, 0x02),
    ): ByteArray = factory.encodeAuthenticatorData(
        rpId = "example.com",
        signCount = 0,
        credentialId = credentialId,
        credentialPublicKey = byteArrayOf(0xa0.toByte()),
        attestation = attestation,
        userVerified = true,
        userPresent = true,
    ).attestedCredentialDataAaguid()

    private fun createAuthenticatorDataFactory() = WebAuthnAuthenticatorDataFactory(
        aaguid = "d548826e79b4db40a3d811116f7e8349".hexToByteArray(),
        hashSha256 = { ByteArray(32) },
    )
}

private const val AUTHENTICATOR_DATA_AAGUID_OFFSET = 32 + 1 + 4
private const val WEBAUTHN_AAGUID_SIZE_BYTES = 16

private fun ByteArray.attestedCredentialDataAaguid(): ByteArray = copyOfRange(
    fromIndex = AUTHENTICATOR_DATA_AAGUID_OFFSET,
    toIndex = AUTHENTICATOR_DATA_AAGUID_OFFSET + WEBAUTHN_AAGUID_SIZE_BYTES,
)
