package com.artemchep.keyguard.util.s3.internal

import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3Operation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal class S3ErrorContext(
    val operation: S3Operation,
    val key: String?,
    val statusCode: Int,
    val error: S3ErrorBody?,
    headers: Headers,
    /** The time used to sign this request, including its clock correction. */
    val now: Instant,
    /** The region that the request was signed for. */
    val signingRegion: String,
) {
    // A HEAD response has no body; MinIO repeats the error code in a header.
    val code: String? = error?.code ?: headers[HEADER_MINIO_ERROR_CODE]?.trimToNull()
    val bucketRegion: String? = headers[HEADER_BUCKET_REGION]?.trimToNull() ?: error?.region
    val serverDate: Instant? = headers[HttpHeaders.Date]?.let(::parseHttpDateOrNull)
    val location: String? = headers[HttpHeaders.Location]?.trimToNull()

    /** The server reported a bucket region other than the signing region. */
    val isWrongRegion: Boolean
        get() = bucketRegion != null && bucketRegion != signingRegion
}

/**
 * Maps an error response to an exception: as a skewed clock first, then by
 * its S3 error code, then by its HTTP status.
 */
internal fun mapS3Error(
    context: S3ErrorContext,
): S3Exception = context.inferClockSkewOrNull()
    ?: context.code
        ?.let { code -> ERROR_CODE_MAPPERS[code] }
        ?.invoke(context)
    ?: mapS3Status(context)

/**
 * A skewed device clock fails the signature check, but not every server
 * reports it as RequestTimeTooSkewed, and a HEAD response cannot report it
 * at all. The Date header of a rejected request reveals it instead; a false
 * positive costs only one retry.
 */
private fun S3ErrorContext.inferClockSkewOrNull(): S3Exception? {
    val date = serverDate
        ?: return null
    val skewed = statusCode in CLOCK_SKEW_STATUSES &&
        code != ERROR_REQUEST_TIME_TOO_SKEWED &&
        (date - now).absoluteValue > CLOCK_SKEW_THRESHOLD
    return if (skewed) S3Exception.ClockSkew(operation, date, statusCode, code) else null
}

private fun mapS3Status(
    context: S3ErrorContext,
): S3Exception = with(context) {
    when (statusCode) {
        in REDIRECT_STATUSES -> mapRedirect(context)
        // AWS answers a HEAD in the wrong region with a bare 400. MinIO
        // names its region in every error, so a code takes precedence.
        STATUS_BAD_REQUEST -> if (code == null && isWrongRegion) {
            S3Exception.WrongRegion(operation, bucketRegion, statusCode, code)
        } else {
            mapOtherStatus(context)
        }
        STATUS_UNAUTHORIZED -> S3Exception.AuthenticationFailed(operation, statusCode, code)
        STATUS_FORBIDDEN -> S3Exception.PermissionDenied(operation, key, statusCode, code)
        STATUS_NOT_FOUND -> S3Exception.NotFound(operation, key, statusCode, code)
        STATUS_PRECONDITION_FAILED -> S3Exception.PreconditionFailed(operation, key, statusCode, code)
        STATUS_RANGE_NOT_SATISFIABLE -> S3Exception.InvalidRange(operation, key, statusCode, code)
        STATUS_INSUFFICIENT_STORAGE -> S3Exception.InsufficientStorage(operation, key, statusCode, code)
        else -> mapOtherStatus(context)
    }
}

private fun mapRedirect(
    context: S3ErrorContext,
): S3Exception = with(context) {
    if (isWrongRegion) {
        S3Exception.WrongRegion(operation, bucketRegion, statusCode, code)
    } else {
        // Not an S3 region redirect, for example a proxy that redirects
        // HTTP to HTTPS.
        S3Exception.Protocol(
            operation = operation,
            key = key,
            message = location
                ?.let { "server redirected the request to $it" }
                ?: "server redirected the request",
            statusCode = statusCode,
            errorCode = code,
        )
    }
}

private fun mapOtherStatus(
    context: S3ErrorContext,
): S3Exception = with(context) {
    // A 409 is retryable only for the codes mapped as transient: others,
    // such as a key that collides with a directory, are permanent.
    val transient = statusCode == STATUS_TOO_MANY_REQUESTS ||
        statusCode >= STATUS_SERVER_ERROR && statusCode !in PERMANENT_SERVER_ERROR_STATUSES
    if (transient) {
        S3Exception.Transient(operation, key, statusCode, code)
    } else {
        S3Exception.Protocol(
            operation = operation,
            key = key,
            message = error?.message ?: if (statusCode == STATUS_NOT_IMPLEMENTED) {
                "server does not implement the request"
            } else {
                "server rejected the request"
            },
            statusCode = statusCode,
            errorCode = code,
        )
    }
}

private val ERROR_CODE_MAPPERS: Map<String, (S3ErrorContext) -> S3Exception> = buildMap {
    put("NoSuchKey") { S3Exception.NotFound(it.operation, it.key, it.statusCode, it.code) }
    put("NoSuchBucket") { S3Exception.BucketNotFound(it.operation, it.statusCode, it.code) }
    listOf(
        "InvalidAccessKeyId",
        "SignatureDoesNotMatch",
        "InvalidToken",
        "ExpiredToken",
        // versitygw answers an unknown access key with a 404.
        "XAdminUserNotFound",
    ).forEach { code ->
        put(code) { S3Exception.AuthenticationFailed(it.operation, it.statusCode, it.code) }
    }
    listOf("AccessDenied", "AllAccessDisabled", "AccountProblem").forEach { code ->
        put(code) { S3Exception.PermissionDenied(it.operation, it.key, it.statusCode, it.code) }
    }
    listOf(
        "PermanentRedirect",
        "TemporaryRedirect",
        "IllegalLocationConstraintException",
    ).forEach { code ->
        put(code) { S3Exception.WrongRegion(it.operation, it.bucketRegion, it.statusCode, it.code) }
    }
    // Also sent for a malformed credential, which does not name a region.
    put("AuthorizationHeaderMalformed") {
        if (it.isWrongRegion) {
            S3Exception.WrongRegion(it.operation, it.bucketRegion, it.statusCode, it.code)
        } else {
            S3Exception.AuthenticationFailed(it.operation, it.statusCode, it.code)
        }
    }
    // The object was archived to a storage class that must be restored first.
    put("InvalidObjectState") {
        S3Exception.Protocol(
            operation = it.operation,
            key = it.key,
            message = it.error?.message ?: "object is archived",
            statusCode = it.statusCode,
            errorCode = it.code,
        )
    }
    put(ERROR_REQUEST_TIME_TOO_SKEWED) {
        S3Exception.ClockSkew(
            operation = it.operation,
            serverTime = it.error?.serverTime ?: it.serverDate,
            statusCode = it.statusCode,
            errorCode = it.code,
        )
    }
    put("InvalidRange") { S3Exception.InvalidRange(it.operation, it.key, it.statusCode, it.code) }
    put("PreconditionFailed") { S3Exception.PreconditionFailed(it.operation, it.key, it.statusCode, it.code) }
    listOf(
        "ConditionalRequestConflict",
        "OperationAborted",
        "RequestTimeout",
        "SlowDown",
        "InternalError",
        "ServiceUnavailable",
        // Throttling codes of the AWS SDKs, sent by some servers with a 400.
        "Throttling",
        "ThrottlingException",
        "ThrottledException",
        "RequestThrottled",
        "RequestThrottledException",
        "TooManyRequestsException",
        "RequestLimitExceeded",
        "BandwidthLimitExceeded",
        "LimitExceededException",
        "PriorRequestNotComplete",
        "RequestTimeoutException",
        "TransactionInProgressException",
    ).forEach { code ->
        put(code) { S3Exception.Transient(it.operation, it.key, it.statusCode, it.code) }
    }
    listOf(
        "XMinioStorageFull",
        "StorageFull",
        // Ceph and MinIO bucket quotas.
        "QuotaExceeded",
        "XMinioAdminBucketQuotaExceeded",
    ).forEach { code ->
        put(code) { S3Exception.InsufficientStorage(it.operation, it.key, it.statusCode, it.code) }
    }
}

internal fun parseHttpDateOrNull(
    value: String,
): Instant? = runCatching { value.fromHttpToGmtDate() }.getOrNull()
    ?.let { date -> Instant.fromEpochMilliseconds(date.timestamp) }

internal fun Int.isSuccessStatus(): Boolean = this in STATUS_OK..STATUS_LAST_SUCCESS

internal const val STATUS_OK = 200
internal const val STATUS_PARTIAL_CONTENT = 206
internal const val STATUS_NOT_FOUND = 404
internal const val STATUS_PRECONDITION_FAILED = 412

private const val STATUS_LAST_SUCCESS = 299
private const val STATUS_BAD_REQUEST = 400
private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_FORBIDDEN = 403
private const val STATUS_RANGE_NOT_SATISFIABLE = 416
private const val STATUS_TOO_MANY_REQUESTS = 429
private const val STATUS_SERVER_ERROR = 500
private const val STATUS_NOT_IMPLEMENTED = 501
private const val STATUS_INSUFFICIENT_STORAGE = 507

private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/** Server errors that a retry does not fix. */
private val PERMANENT_SERVER_ERROR_STATUSES = setOf(STATUS_NOT_IMPLEMENTED, 505, 508, 510)

private val CLOCK_SKEW_STATUSES = setOf(STATUS_BAD_REQUEST, STATUS_UNAUTHORIZED, STATUS_FORBIDDEN)

private const val HEADER_BUCKET_REGION = "x-amz-bucket-region"
private const val HEADER_MINIO_ERROR_CODE = "x-minio-error-code"

private const val ERROR_REQUEST_TIME_TOO_SKEWED = "RequestTimeTooSkewed"

private val CLOCK_SKEW_THRESHOLD = 10.minutes
