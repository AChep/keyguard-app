package com.artemchep.keyguard.util.s3

import com.artemchep.keyguard.util.foundation.crypto.createHmacSha256
import com.artemchep.keyguard.util.foundation.crypto.sha256
import kotlin.concurrent.Volatile
import kotlin.time.Instant

/**
 * Signs requests with AWS Signature Version 4. The payload is signed by its
 * SHA-256 hash, or left out of the signature with [UNSIGNED_PAYLOAD].
 *
 * The caller provides the canonical URI and query exactly as they are sent
 * on the wire, and the headers to sign. `host` must be one of them.
 */
class S3SigV4Signer(
    private val credentials: S3Credentials,
    private val region: String,
    private val service: String = "s3",
) {
    /** The signing key only changes with the date, so it is derived once per day. */
    @Volatile
    private var cachedSigningKey: Pair<String, ByteArray>? = null

    /**
     * Returns the headers to add to the request: `x-amz-date`,
     * `x-amz-content-sha256` and `Authorization`.
     */
    fun sign(
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        headers: Map<String, String>,
        payloadSha256Hex: String,
        now: Instant,
    ): Map<String, String> {
        val amzDate = formatAmzDate(now)
        val allHeaders = headers.entries
            .groupBy { it.key.lowercase() }
            .mapValues { (_, values) ->
                values.joinToString(separator = ",") { it.value.canonicalHeaderValue() }
            } + mapOf(
                HEADER_AMZ_DATE to amzDate,
                HEADER_AMZ_CONTENT_SHA256 to payloadSha256Hex,
            )
        val canonicalHeaders = allHeaders
            .entries
            .sortedBy { it.key }
            .associate { it.key to it.value }
        require(HEADER_HOST in canonicalHeaders) {
            "SigV4 requests must sign the host header."
        }
        val signedHeaders = canonicalHeaders.keys.joinToString(separator = ";")
        val canonicalRequest = canonicalRequest(
            method = method,
            canonicalUri = canonicalUri,
            canonicalQuery = canonicalQuery,
            canonicalHeaders = canonicalHeaders,
            signedHeaders = signedHeaders,
            payloadSha256Hex = payloadSha256Hex,
        )
        val date = amzDate.substring(0, DATE_LENGTH)
        val scope = "$date/$region/$service/$TERMINATOR"
        val stringToSign = stringToSign(
            amzDate = amzDate,
            scope = scope,
            canonicalRequest = canonicalRequest,
        )
        val signature = hmacSha256(signingKeyFor(date), stringToSign.encodeToByteArray()).toHexString()
        val authorization = "$ALGORITHM Credential=${credentials.accessKeyId}/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
        return mapOf(
            HEADER_AMZ_DATE to amzDate,
            HEADER_AMZ_CONTENT_SHA256 to payloadSha256Hex,
            HEADER_AUTHORIZATION to authorization,
        )
    }

    internal fun canonicalRequest(
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        canonicalHeaders: Map<String, String>,
        signedHeaders: String,
        payloadSha256Hex: String,
    ): String = buildString {
        append(method)
        append('\n')
        append(canonicalUri)
        append('\n')
        append(canonicalQuery)
        append('\n')
        canonicalHeaders.forEach { (name, value) ->
            append(name)
            append(':')
            append(value)
            append('\n')
        }
        append('\n')
        append(signedHeaders)
        append('\n')
        append(payloadSha256Hex)
    }

    internal fun stringToSign(
        amzDate: String,
        scope: String,
        canonicalRequest: String,
    ): String = buildString {
        append(ALGORITHM)
        append('\n')
        append(amzDate)
        append('\n')
        append(scope)
        append('\n')
        append(sha256(canonicalRequest.encodeToByteArray()).toHexString())
    }

    private fun signingKeyFor(
        date: String,
    ): ByteArray {
        cachedSigningKey
            ?.takeIf { it.first == date }
            ?.let { return it.second }
        return signingKey(date)
            .also { cachedSigningKey = date to it }
    }

    internal fun signingKey(
        date: String,
    ): ByteArray {
        val dateKey = hmacSha256("AWS4${credentials.secretAccessKey}".encodeToByteArray(), date.encodeToByteArray())
        val regionKey = hmacSha256(dateKey, region.encodeToByteArray())
        val serviceKey = hmacSha256(regionKey, service.encodeToByteArray())
        return hmacSha256(serviceKey, TERMINATOR.encodeToByteArray())
    }

    companion object {
        const val HEADER_AMZ_DATE = "x-amz-date"
        const val HEADER_AMZ_CONTENT_SHA256 = "x-amz-content-sha256"
        const val HEADER_AUTHORIZATION = "Authorization"

        /** The SHA-256 of an empty payload, in hex. */
        const val EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        /** Signs a request without its payload; only safe over HTTPS. */
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

        private const val HEADER_HOST = "host"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val TERMINATOR = "aws4_request"
        private const val DATE_LENGTH = 8

        /** Formats [instant] as `yyyyMMdd'T'HHmmss'Z'`. */
        internal fun formatAmzDate(
            instant: Instant,
        ): String = Instant.fromEpochSeconds(instant.epochSeconds)
            .toString()
            .filter { it != '-' && it != ':' }
    }
}

private fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray = createHmacSha256(key).run {
    update(data)
    doFinal()
}

private fun String.canonicalHeaderValue(): String = trim().replace(WHITESPACE_REGEX, " ")

private val WHITESPACE_REGEX = Regex("\\s+")
