package com.artemchep.keyguard.util.s3

import kotlin.time.Instant

data class S3ClientConfig(
    /** The service endpoint, for example `https://minio.lan:9000`. Blank means Amazon S3. */
    val endpoint: String? = null,
    /** The signing region. Blank means [S3Endpoints.DEFAULT_REGION]. */
    val region: String? = null,
    val bucket: String,
    val credentials: S3Credentials,
    /**
     * Address the bucket as a path segment instead of a subdomain. Path-style
     * is also used when the bucket cannot be a subdomain of the endpoint: for
     * an IP address, or for a bucket with dots over HTTPS, which a wildcard
     * certificate does not cover.
     */
    val pathStyle: Boolean = true,
    val userAgent: String? = null,
)

data class S3Credentials(
    val accessKeyId: String,
    val secretAccessKey: String,
) {
    init {
        require(accessKeyId.isNotBlank()) {
            "S3 access key ID must not be blank."
        }
        require(isValidS3AccessKeyId(accessKeyId)) {
            "S3 access key ID must contain only visible ASCII characters."
        }
        require(secretAccessKey.isNotEmpty()) {
            "S3 secret access key must not be empty."
        }
    }

    override fun toString(): String = "S3Credentials(accessKeyId=$accessKeyId, secretAccessKey=<redacted>)"
}

object S3Endpoints {
    const val DEFAULT_REGION = "us-east-1"

    fun aws(
        region: String,
    ): String {
        val domain = AWS_PARTITION_DOMAINS.entries
            .firstOrNull { (prefix, _) -> region.startsWith(prefix) }
            ?.value
            ?: "amazonaws.com"
        return "https://s3.$region.$domain"
    }

    /** Region prefixes of the AWS partitions outside the `amazonaws.com` domain. */
    private val AWS_PARTITION_DOMAINS = mapOf(
        "cn-" to "amazonaws.com.cn",
        "eusc-" to "amazonaws.eu",
        "us-iso-" to "c2s.ic.gov",
        "us-isob-" to "sc2s.sgov.gov",
        "us-isof-" to "csp.hci.ic.gov",
        "eu-isoe-" to "cloud.adc-e.uk",
    )
}

sealed interface S3WritePrecondition {
    /** Fail if an object already exists under the key. */
    data object IfNoneMatch : S3WritePrecondition

    /** Fail unless the current object has this ETag. */
    data class IfMatch(
        val etag: String,
    ) : S3WritePrecondition {
        init {
            require(etag.isNotBlank()) {
                "S3 ETag precondition must not be blank."
            }
        }
    }
}

data class S3ByteRange(
    val offset: Long,
    val length: Long? = null,
) {
    init {
        require(offset >= 0L) {
            "S3 read offset must not be negative."
        }
        require(length == null || length > 0L) {
            "S3 read length must be positive."
        }
    }
}

data class S3Object(
    val key: String,
    val size: Long?,
    val lastModified: Instant?,
    val etag: String?,
)

data class S3ListPage(
    val objects: List<S3Object>,
    /** Prefixes rolled up by the delimiter, each ending with the delimiter. */
    val commonPrefixes: List<String>,
    /** Pass to the next request to continue the listing; null on the last page. */
    val nextContinuationToken: String?,
)

enum class S3Operation {
    Head,
    Get,
    Put,
    List,
    Delete,
}

sealed class S3Exception(
    val operation: S3Operation,
    val key: String?,
    val statusCode: Int?,
    val errorCode: String?,
    val retryable: Boolean,
    reason: String,
    cause: Throwable? = null,
) : Exception(s3Message(operation, key, statusCode, errorCode, reason), cause) {
    class NotFound(
        operation: S3Operation,
        key: String?,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, key, statusCode, errorCode, false, "object was not found", cause)

    class BucketNotFound(
        operation: S3Operation,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, null, statusCode, errorCode, false, "bucket was not found", cause)

    class AlreadyExists(
        operation: S3Operation,
        key: String?,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, key, statusCode, errorCode, false, "object already exists", cause)

    class PreconditionFailed(
        operation: S3Operation,
        key: String?,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, key, statusCode, errorCode, false, "object precondition failed", cause)

    class InvalidRange(
        operation: S3Operation,
        key: String?,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, key, statusCode, errorCode, false, "object does not contain requested range", cause)

    class AuthenticationFailed(
        operation: S3Operation,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, null, statusCode, errorCode, false, "authentication failed", cause)

    class PermissionDenied(
        operation: S3Operation,
        key: String?,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, key, statusCode, errorCode, false, "permission denied", cause)

    class WrongRegion(
        operation: S3Operation,
        val expectedRegion: String?,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(
        operation,
        null,
        statusCode,
        errorCode,
        false,
        expectedRegion
            ?.let { "bucket is in region '$it'" }
            ?: "bucket is in a different region",
        cause,
    )

    class ClockSkew(
        operation: S3Operation,
        /** The server time, when the response reported it. */
        val serverTime: Instant?,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, null, statusCode, errorCode, false, "device clock differs from the server clock", cause)

    class InsufficientStorage(
        operation: S3Operation,
        key: String?,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, key, statusCode, errorCode, false, "insufficient remote storage", cause)

    class Transient(
        operation: S3Operation,
        key: String?,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) : S3Exception(operation, key, statusCode, errorCode, true, "transient failure", cause)

    class Protocol(
        operation: S3Operation,
        key: String?,
        message: String,
        statusCode: Int? = null,
        errorCode: String? = null,
        cause: Throwable? = null,
        retryable: Boolean = false,
    ) : S3Exception(operation, key, statusCode, errorCode, retryable, message, cause)
}

private fun s3Message(
    operation: S3Operation,
    key: String?,
    statusCode: Int?,
    errorCode: String?,
    reason: String,
): String {
    val target = key?.let { " for '$it'" }.orEmpty()
    val status = listOfNotNull(
        statusCode?.let { "HTTP $it" },
        errorCode,
    ).takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = " (", postfix = ")")
        .orEmpty()
    return "S3 ${operation.name} failed$target$status: $reason."
}
