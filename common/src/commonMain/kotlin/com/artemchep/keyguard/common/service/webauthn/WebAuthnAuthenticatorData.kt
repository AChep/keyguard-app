package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskeyAttestation

// WebAuthn bit 0: User Present (UP) result.
private const val WEBAUTHN_AUTH_DATA_FLAG_USER_PRESENT = 0b00000001

// WebAuthn bit 2: User Verified (UV) result.
private const val WEBAUTHN_AUTH_DATA_FLAG_USER_VERIFIED = 0b00000100

// WebAuthn bit 3: Backup Eligibility (BE).
private const val WEBAUTHN_AUTH_DATA_FLAG_BACKUP_ELIGIBILITY = 0b00001000

// WebAuthn bit 4: Backup State (BS).
private const val WEBAUTHN_AUTH_DATA_FLAG_BACKUP_STATE = 0b00010000

// WebAuthn bit 6: Attested credential data included (AT).
private const val WEBAUTHN_AUTH_DATA_FLAG_ATTESTED_CREDENTIAL_DATA = 0b01000000

// WebAuthn bit 7: Extension data included (ED).
private const val WEBAUTHN_AUTH_DATA_FLAG_EXTENSION_DATA = 0b10000000

private val BITWARDEN_AAGUID = byteArrayOf(
    0xd5.toByte(),
    0x48.toByte(),
    0x82.toByte(),
    0x6e.toByte(),
    0x79.toByte(),
    0xb4.toByte(),
    0xdb.toByte(),
    0x40.toByte(),
    0xa3.toByte(),
    0xd8.toByte(),
    0x11.toByte(),
    0x11.toByte(),
    0x6f.toByte(),
    0x7e.toByte(),
    0x83.toByte(),
    0x49.toByte(),
)

internal class WebAuthnAuthenticatorDataFactory(
    private val cryptoService: CryptoGenerator,
) {
    fun encodeAuthenticatorData(
        rpId: String,
        signCount: Int,
        credentialId: ByteArray,
        credentialPublicKey: ByteArray?,
        attestation: CreatePasskeyAttestation? = null,
        userVerified: Boolean,
        userPresent: Boolean,
    ): ByteArray {
        val rpIdHash = cryptoService.hashSha256(rpId.encodeToByteArray())
        val attestedCredentialData = credentialPublicKey?.let {
            WebAuthnAttestedCredentialData(
                aaguid = aaguidFor(
                    attestation = attestation,
                    credentialId = credentialId,
                ),
                credentialId = credentialId,
                credentialPublicKey = credentialPublicKey,
            )
        }

        return WebAuthnAuthenticatorData(
            rpIdHash = rpIdHash,
            flags = WebAuthnAuthenticatorDataFlags(
                userPresent = userPresent,
                userVerified = userVerified,
                backupEligibility = true,
                backupState = true,
                attestedCredentialData = attestedCredentialData != null,
                extensionData = false,
            ),
            signCount = signCount,
            attestedCredentialData = attestedCredentialData,
            extensions = null,
        ).encode()
    }

    private fun aaguidFor(
        attestation: CreatePasskeyAttestation?,
        credentialId: ByteArray,
    ): ByteArray = when (attestation) {
        // Convey the authenticator's AAGUID and attestation statement,
        // unaltered, to the Relying Party.
        CreatePasskeyAttestation.DIRECT,
        CreatePasskeyAttestation.ENTERPRISE,
            -> BITWARDEN_AAGUID

        // The client may replace the AAGUID and attestation statement with a
        // more privacy-friendly and/or more easily verifiable version.
        CreatePasskeyAttestation.INDIRECT -> {
            val hash = cryptoService.hashMd5(BITWARDEN_AAGUID + credentialId)
            if (hash.size == AAGUID_SIZE_BYTES) {
                hash
            } else {
                hash.sliceArray(0 until AAGUID_SIZE_BYTES)
            }
        }

        // WebAuthn L3 defines `attestation = "none"` as the creation-options
        // default and says None attestation is used to "replace any
        // authenticator-provided attestation statement". The create()
        // conveyance handling sets `fmt` to "none" and `attStmt` to an empty
        // map; it does not replace the Section 6 authenticator AAGUID, a
        // "128-bit identifier indicating the type (e.g. make and model)".
        //
        // Spec:
        // - https://www.w3.org/TR/webauthn-3/#sctn-createCredential
        // - https://www.w3.org/TR/webauthn-3/#sctn-none-attestation
        // - https://www.w3.org/TR/webauthn-3/#sctn-authenticator-model
        null,
        CreatePasskeyAttestation.NONE,
            -> BITWARDEN_AAGUID
    }
}

private const val AAGUID_SIZE_BYTES = 16
private const val AUTHENTICATOR_DATA_FLAGS_SIZE_BYTES = 1
private const val CREDENTIAL_ID_LENGTH_SIZE_BYTES = 2
private const val MAX_CREDENTIAL_ID_SIZE_BYTES = 1023

private data class WebAuthnAuthenticatorData(
    val rpIdHash: ByteArray,
    val flags: WebAuthnAuthenticatorDataFlags,
    val signCount: Int,
    val attestedCredentialData: WebAuthnAttestedCredentialData?,
    val extensions: ByteArray?,
) {
    init {
        require(signCount >= 0) {
            "WebAuthn signCount must be non-negative."
        }
    }

    fun encode(): ByteArray {
        val attestedCredentialDataBytes = attestedCredentialData?.encode() ?: ByteArray(0)
        val extensionBytes = extensions ?: ByteArray(0)
        val size = rpIdHash.size +
                AUTHENTICATOR_DATA_FLAGS_SIZE_BYTES +
                Int.SIZE_BYTES +
                attestedCredentialDataBytes.size +
                extensionBytes.size

        return ByteArray(size).also { bytes ->
            var offset = 0
            offset = bytes.writeBytes(offset, rpIdHash)
            bytes[offset] = flags.toByte()
            offset += AUTHENTICATOR_DATA_FLAGS_SIZE_BYTES
            offset = bytes.writeInt(offset, signCount)
            offset = bytes.writeBytes(offset, attestedCredentialDataBytes)
            bytes.writeBytes(offset, extensionBytes)
        }
    }
}

private data class WebAuthnAttestedCredentialData(
    val aaguid: ByteArray,
    val credentialId: ByteArray,
    val credentialPublicKey: ByteArray,
) {
    init {
        require(aaguid.size == AAGUID_SIZE_BYTES) {
            "WebAuthn AAGUID must be $AAGUID_SIZE_BYTES bytes."
        }
        // Web Authentication: An API for accessing Public Key Credentials Level 3,
        // §6.5.1 Attested Credential Data: "Value MUST be ≤ 1023."
        // §7.1 Registering a New Credential: "Verify that the credentialId is ≤ 1023 bytes."
        // https://www.w3.org/TR/webauthn-3/
        require(credentialId.size <= MAX_CREDENTIAL_ID_SIZE_BYTES) {
            "WebAuthn credential ID must be at most $MAX_CREDENTIAL_ID_SIZE_BYTES bytes."
        }
    }

    fun encode(): ByteArray {
        val size = aaguid.size +
                CREDENTIAL_ID_LENGTH_SIZE_BYTES +
                credentialId.size +
                credentialPublicKey.size
        return ByteArray(size).also { bytes ->
            var offset = 0
            offset = bytes.writeBytes(offset, aaguid)
            offset = bytes.writeShort(offset, credentialId.size)
            offset = bytes.writeBytes(offset, credentialId)
            bytes.writeBytes(offset, credentialPublicKey)
        }
    }
}

internal data class WebAuthnAuthenticatorDataFlags(
    val extensionData: Boolean,
    val attestedCredentialData: Boolean,
    val backupState: Boolean,
    val backupEligibility: Boolean,
    val userVerified: Boolean,
    val userPresent: Boolean,
) {
    fun toByte(): Byte {
        var flags = 0
        if (extensionData) {
            flags = flags or WEBAUTHN_AUTH_DATA_FLAG_EXTENSION_DATA
        }
        if (attestedCredentialData) {
            flags = flags or WEBAUTHN_AUTH_DATA_FLAG_ATTESTED_CREDENTIAL_DATA
        }
        if (backupState) {
            flags = flags or WEBAUTHN_AUTH_DATA_FLAG_BACKUP_STATE
        }
        if (backupEligibility) {
            flags = flags or WEBAUTHN_AUTH_DATA_FLAG_BACKUP_ELIGIBILITY
        }
        if (userVerified) {
            flags = flags or WEBAUTHN_AUTH_DATA_FLAG_USER_VERIFIED
        }
        if (userPresent) {
            flags = flags or WEBAUTHN_AUTH_DATA_FLAG_USER_PRESENT
        }
        return flags.toByte()
    }
}

// WebAuthn authenticator data flags use bit 0 as the least significant bit:
// https://w3c.github.io/webauthn/#authenticator-data
internal fun authDataFlags(
    extensionData: Boolean,
    attestationData: Boolean,
    backupState: Boolean,
    backupEligibility: Boolean,
    userVerification: Boolean,
    userPresence: Boolean,
): Byte = WebAuthnAuthenticatorDataFlags(
    extensionData = extensionData,
    attestedCredentialData = attestationData,
    backupState = backupState,
    backupEligibility = backupEligibility,
    userVerified = userVerification,
    userPresent = userPresence,
).toByte()

private fun ByteArray.writeBytes(
    offset: Int,
    data: ByteArray,
): Int {
    data.copyInto(
        destination = this,
        destinationOffset = offset,
    )
    return offset + data.size
}

private fun ByteArray.writeShort(
    offset: Int,
    value: Int,
): Int {
    this[offset] = (value ushr Byte.SIZE_BITS).toByte()
    this[offset + 1] = value.toByte()
    return offset + Short.SIZE_BYTES
}

private fun ByteArray.writeInt(
    offset: Int,
    value: Int,
): Int {
    for (index in 0 until Int.SIZE_BYTES) {
        this[offset + index] = (value ushr (Int.SIZE_BITS - Byte.SIZE_BITS * (index + 1))).toByte()
    }
    return offset + Int.SIZE_BYTES
}
