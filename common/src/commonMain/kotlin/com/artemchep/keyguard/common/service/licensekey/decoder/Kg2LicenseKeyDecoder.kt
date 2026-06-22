package com.artemchep.keyguard.common.service.licensekey.decoder

import com.artemchep.keyguard.common.service.licensekey.LicenseSignatureVerifier
import kotlin.io.encoding.Base64

private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val LICENSE_VERSION = 2
private const val LICENSE_ID_BYTE_LENGTH = 10
private const val LICENSE_PAYLOAD_BYTE_LENGTH = 14
private const val LIFETIME_EXPIRY_YEAR_MONTH = "9999-12"
private const val LIFETIME_EXPIRY_MONTH_CODE = 0xffff
private const val EXPIRY_BASE_YEAR = 2000

private val licensePrefixByKeyId = mapOf(
    KeyguardKg2LicensePublicKeys.CURRENT_KEY_ID to "KG2A",
)
private val keyIdByLicensePrefix = licensePrefixByKeyId.entries.associate { (keyId, prefix) ->
    prefix to keyId
}
private val tierNames = mapOf(
    1 to "premium",
)
private val productKindNames = mapOf(
    1 to Kg2LicenseProductKind.Subscription,
    2 to Kg2LicenseProductKind.Lifetime,
)
private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

data class Kg2LicenseKeyMetadata(
    val licenseId: String,
    val tier: String,
    val productKind: Kg2LicenseProductKind,
    val expiryYearMonth: String,
    val keyId: String,
) {
    val isLifetime: Boolean
        get() = productKind == Kg2LicenseProductKind.Lifetime ||
                expiryYearMonth == LIFETIME_EXPIRY_YEAR_MONTH
}

enum class Kg2LicenseProductKind(
    val wireName: String,
) {
    Subscription("subscription"),
    Lifetime("lifetime");
}

class Kg2LicenseKeyDecoder(
    private val signatureVerifier: LicenseSignatureVerifier,
    private val publicKeysById: Map<String, String> = KeyguardKg2LicensePublicKeys.values,
) {
    fun decodeOrNull(licenseKey: String): Kg2LicenseKeyMetadata? =
        runCatching {
            decode(licenseKey)
        }.getOrNull()

    private fun decode(licenseKey: String): Kg2LicenseKeyMetadata {
        val parts = licenseKey.trim().split(".")
        require(parts.size == 3)

        val prefix = parts[0]
        val encodedPayload = parts[1]
        val encodedSignature = parts[2]
        val keyId = keyIdByLicensePrefix.getValue(prefix)
        val publicKeyPem = publicKeysById.getValue(keyId)

        val payload = base64Url.decode(encodedPayload)
        require(payload.size == LICENSE_PAYLOAD_BYTE_LENGTH)

        val version = payload[0].toInt() and 0xff
        require(version == LICENSE_VERSION)

        val flags = payload[11].toInt() and 0xff
        val tier = tierNames.getValue(flags ushr 4)
        val productKind = productKindNames.getValue(flags and 0x0f)
        val expiryCodeHigh = (payload[12].toInt() and 0xff) shl 8
        val expiryCodeLow = payload[13].toInt() and 0xff
        val expiryCode = expiryCodeHigh or expiryCodeLow
        val expiryYearMonth = requireNotNull(
            yearMonthFromExpiryCode(
                productKind = productKind,
                value = expiryCode,
            ),
        )

        val signature = base64Url.decode(encodedSignature)
        require(signature.size == 64)

        val signingInput = "$prefix.$encodedPayload".encodeToByteArray()
        require(
            signatureVerifier.verify(
                publicKeyPem = publicKeyPem,
                signingInput = signingInput,
                signature = signature,
            ),
        )

        return Kg2LicenseKeyMetadata(
            licenseId = encodeLicenseIdBytes(payload.copyOfRange(1, 11)),
            tier = tier,
            productKind = productKind,
            expiryYearMonth = expiryYearMonth,
            keyId = keyId,
        )
    }
}

object KeyguardKg2LicensePublicKeys {
    const val CURRENT_KEY_ID: String = "kg2-p256-v1"

    val values: Map<String, String> = mapOf(
        CURRENT_KEY_ID to """
            -----BEGIN PUBLIC KEY-----
            MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEgZ6zj/ijWyegEKKIgNlKxIvRXkFZ
            r3CLtp8Bit1oZbBH6AUB5wh7DYgkriQraUKAVgTGOpExRfvMFEC+duSldQ==
            -----END PUBLIC KEY-----
        """.trimIndent(),
    )
}

private fun yearMonthFromExpiryCode(
    productKind: Kg2LicenseProductKind,
    value: Int,
): String? {
    if (productKind == Kg2LicenseProductKind.Lifetime) {
        return if (value == LIFETIME_EXPIRY_MONTH_CODE) {
            LIFETIME_EXPIRY_YEAR_MONTH
        } else {
            null
        }
    }
    if (value == LIFETIME_EXPIRY_MONTH_CODE) {
        return null
    }

    val year = EXPIRY_BASE_YEAR + value / 12
    val month = value % 12 + 1
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"
}

private fun encodeLicenseIdBytes(bytes: ByteArray): String {
    require(bytes.size == LICENSE_ID_BYTE_LENGTH)

    val output = StringBuilder(16)
    var buffer = 0
    var bitCount = 0
    bytes.forEach { byte ->
        buffer = (buffer shl 8) or (byte.toInt() and 0xff)
        bitCount += 8
        while (bitCount >= 5) {
            bitCount -= 5
            output.append(ALPHABET[(buffer shr bitCount) and 0x1f])
            buffer = buffer and ((1 shl bitCount) - 1)
        }
    }
    return output.toString()
}
