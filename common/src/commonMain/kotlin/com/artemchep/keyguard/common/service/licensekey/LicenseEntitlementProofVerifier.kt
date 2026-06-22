package com.artemchep.keyguard.common.service.licensekey

import com.artemchep.keyguard.common.service.licensekey.entity.EntitlementProofHeaderEntity
import com.artemchep.keyguard.common.service.licensekey.entity.EntitlementProofPayloadEntity
import com.artemchep.keyguard.common.service.licensekey.entity.LicenseEntitlementEntity
import com.artemchep.keyguard.common.service.licensekey.entity.SignedEntitlementEntity
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

private const val ENTITLEMENT_PROOF_TYPE = "keyguard.license.entitlement.v1"
private const val ENTITLEMENT_PROOF_VERSION = 1
private const val ENTITLEMENT_PROOF_ALGORITHM = "ES256"
private const val REQUEST_HASH_DOMAIN = "Keyguard License Entitlement Request v1"
private const val CLOCK_SKEW_SECONDS = 300L
private const val MAX_PROOF_TTL_SECONDS = 300L

class LicenseEntitlementProofVerifier(
    private val signatureVerifier: LicenseSignatureVerifier,
    private val json: Json,
    private val publicKeysById: Map<String, String> = KeyguardLicenseStatusProofPublicKeys.values,
    private val now: () -> Instant = { Clock.System.now() },
) {
    fun verify(
        signed: SignedEntitlementEntity,
        expectation: LicenseEntitlementProofExpectation,
    ): LicenseEntitlementEntity? = runCatching {
        val header = decodeJson<EntitlementProofHeaderEntity>(signed.protectedHeader)
            ?: return@runCatching null
        if (header.alg != ENTITLEMENT_PROOF_ALGORITHM ||
            header.typ != ENTITLEMENT_PROOF_TYPE
        ) {
            return@runCatching null
        }
        val publicKey = publicKeysById[header.kid]
            ?: return@runCatching null
        val signature = base64Url.decode(signed.signature)
        val signingInput = "${signed.protectedHeader}.${signed.payload}".encodeToByteArray()
        val verified = signatureVerifier.verify(
            publicKeyPem = publicKey,
            signingInput = signingInput,
            signature = signature,
        )
        if (!verified) {
            return@runCatching null
        }

        val payload = decodeJson<EntitlementProofPayloadEntity>(signed.payload)
            ?: return@runCatching null
        if (payload.v != ENTITLEMENT_PROOF_VERSION ||
            payload.challenge != expectation.challenge ||
            payload.request.kind != expectation.requestKind ||
            payload.request.hash != expectation.requestHash
        ) {
            return@runCatching null
        }

        val nowSeconds = now().epochSeconds
        val proofTtlSeconds = payload.exp - payload.iat
        if (proofTtlSeconds !in 1..MAX_PROOF_TTL_SECONDS ||
            payload.exp <= nowSeconds ||
            payload.iat > nowSeconds + CLOCK_SKEW_SECONDS
        ) {
            return@runCatching null
        }
        payload.entitlement
    }.getOrNull()

    private inline fun <reified T> decodeJson(value: String): T? =
        runCatching {
            json.decodeFromString<T>(
                base64Url
                    .decode(value)
                    .decodeToString(),
            )
        }.getOrNull()
}

data class LicenseEntitlementProofExpectation(
    val challenge: String,
    val requestKind: String,
    val requestHash: String,
)

object LicenseEntitlementRequestKind {
    const val CLAIM_APPLE: String = "claim_apple"
    const val CLAIM_GOOGLE: String = "claim_google"
    const val LICENSE_STATUS: String = "license_status"
}

fun licenseEntitlementRequestHash(
    kind: String,
    values: List<String>,
    hashSha256: (ByteArray) -> ByteArray,
): String {
    val input = buildString {
        append(REQUEST_HASH_DOMAIN)
        append('\n')
        append(kind)
        values.forEach { value ->
            append('\n')
            append(value.encodeToByteArray().size)
            append(':')
            append(value)
        }
    }
    return base64Url.encode(hashSha256(input.encodeToByteArray()))
}

object KeyguardLicenseStatusProofPublicKeys {
    const val CURRENT_KEY_ID: String = "kg-status-p256-v1"

    val values: Map<String, String> = mapOf(
        CURRENT_KEY_ID to """
            -----BEGIN PUBLIC KEY-----
            MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBkhQALc8SDVs7rdpOYCTq2bZks9t
            /oZsj68QtIdocV83+x9EOSHtpPNoZ/gEdgR41Gl2oThNUa1cu2ryc1S2hA==
            -----END PUBLIC KEY-----
        """.trimIndent(),
    )
}

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
