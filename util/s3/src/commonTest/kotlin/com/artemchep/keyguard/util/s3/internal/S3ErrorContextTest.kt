package com.artemchep.keyguard.util.s3.internal

import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3Operation
import com.artemchep.keyguard.util.s3.testS3Time
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class S3ErrorContextTest {
    @Test
    fun `every supported error code takes precedence over status`() {
        val groups = mapOf<KClass<out S3Exception>, List<String>>(
            S3Exception.NotFound::class to listOf("NoSuchKey"),
            S3Exception.BucketNotFound::class to listOf("NoSuchBucket"),
            S3Exception.AuthenticationFailed::class to listOf(
                "InvalidAccessKeyId",
                "SignatureDoesNotMatch",
                "InvalidToken",
                "ExpiredToken",
                "XAdminUserNotFound",
                // Without a region, it reports a malformed credential.
                "AuthorizationHeaderMalformed",
            ),
            S3Exception.PermissionDenied::class to listOf("AccessDenied", "AllAccessDisabled", "AccountProblem"),
            S3Exception.WrongRegion::class to listOf(
                "PermanentRedirect",
                "TemporaryRedirect",
                "IllegalLocationConstraintException",
            ),
            S3Exception.ClockSkew::class to listOf("RequestTimeTooSkewed"),
            S3Exception.InvalidRange::class to listOf("InvalidRange"),
            S3Exception.PreconditionFailed::class to listOf("PreconditionFailed"),
            S3Exception.Transient::class to listOf(
                "ConditionalRequestConflict",
                "OperationAborted",
                "RequestTimeout",
                "SlowDown",
                "InternalError",
                "ServiceUnavailable",
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
            ),
            S3Exception.InsufficientStorage::class to listOf(
                "XMinioStorageFull",
                "StorageFull",
                "QuotaExceeded",
                "XMinioAdminBucketQuotaExceeded",
            ),
            S3Exception.Protocol::class to listOf("InvalidObjectState"),
        )
        for ((expected, codes) in groups) {
            for (code in codes) {
                val error = mapS3Error(context(status = 418, code = code))
                assertEquals(expected, error::class, code)
                assertEquals(S3Operation.Get, error.operation)
                assertEquals(418, error.statusCode)
                assertEquals(code, error.errorCode)
                assertEquals(expected == S3Exception.Transient::class, error.retryable)
            }
        }
    }

    @Test
    fun `unknown codes fall back to HTTP status without losing error context`() {
        val cases = mapOf(
            // Without a region, a redirect does not come from S3.
            301 to S3Exception.Protocol::class,
            307 to S3Exception.Protocol::class,
            401 to S3Exception.AuthenticationFailed::class,
            403 to S3Exception.PermissionDenied::class,
            404 to S3Exception.NotFound::class,
            // A key that collides with a directory is not transient.
            409 to S3Exception.Protocol::class,
            412 to S3Exception.PreconditionFailed::class,
            416 to S3Exception.InvalidRange::class,
            429 to S3Exception.Transient::class,
            500 to S3Exception.Transient::class,
            501 to S3Exception.Protocol::class,
            502 to S3Exception.Transient::class,
            503 to S3Exception.Transient::class,
            504 to S3Exception.Transient::class,
            505 to S3Exception.Protocol::class,
            507 to S3Exception.InsufficientStorage::class,
            508 to S3Exception.Protocol::class,
            510 to S3Exception.Protocol::class,
            // Cloudflare in front of the server.
            522 to S3Exception.Transient::class,
            400 to S3Exception.Protocol::class,
            302 to S3Exception.Protocol::class,
        )
        for ((status, expected) in cases) {
            for (code in listOf(null, "UnknownProviderCode")) {
                val error = mapS3Error(context(status, code))
                assertEquals(expected, error::class, "$status/$code")
                assertEquals(status, error.statusCode)
                assertEquals(code, error.errorCode)
            }
        }
    }

    @Test
    fun `clock inference uses a strict ten minute threshold in either direction`() {
        val headers = headersOf(HttpHeaders.Date, "Thu, 29 Feb 2024 23:59:58 GMT")
        for (delta in listOf(-601, -600, 0, 600, 601)) {
            val now = testS3Time + delta.seconds
            val context = S3ErrorContext(S3Operation.Head, "key", 403, null, headers, now, REGION)
            val expected = if (delta in -600..600) S3Exception.PermissionDenied::class else S3Exception.ClockSkew::class
            assertEquals(expected, mapS3Error(context)::class, "$delta")
        }
        assertIs<S3Exception.PermissionDenied>(mapS3Error(context(403, "AccessDenied", headers)))
        assertIs<S3Exception.PermissionDenied>(
            mapS3Error(context(403, headers = headersOf(HttpHeaders.Date, "invalid"))),
        )
    }

    @Test
    fun `a skewed Date header explains rejected signatures whatever their code`() {
        val skewed = headersOf(HttpHeaders.Date, "Fri, 01 Mar 2024 01:00:00 GMT")
        // Garage answers a request that is too old with a 400.
        for ((status, code) in listOf(403 to "SignatureDoesNotMatch", 401 to null, 400 to "InvalidRequest")) {
            val error = mapS3Error(context(status, code, skewed))
            assertEquals(testS3Time + 60.minutes + 2.seconds, assertIs<S3Exception.ClockSkew>(error).serverTime)
            assertEquals(code, error.errorCode)
        }
        assertIs<S3Exception.AuthenticationFailed>(mapS3Error(context(403, "SignatureDoesNotMatch")))
        assertIs<S3Exception.NotFound>(mapS3Error(context(404, "NoSuchKey", skewed)))
    }

    @Test
    fun `only a differing region turns a redirect or bad request into a wrong region`() {
        for (status in listOf(301, 302, 303, 307, 308, 400)) {
            val elsewhere = mapS3Error(context(status, headers = headersOf("x-amz-bucket-region", "eu-west-1")))
            assertEquals("eu-west-1", assertIs<S3Exception.WrongRegion>(elsewhere, "$status").expectedRegion)
            // MinIO sends its own region with every response.
            val here = mapS3Error(context(status, headers = headersOf("x-amz-bucket-region", REGION)))
            assertIs<S3Exception.Protocol>(here, "$status")
        }
        val proxy = mapS3Error(context(301, headers = headersOf(HttpHeaders.Location, "https://example.com/bucket")))
        assertTrue("https://example.com/bucket" in assertIs<S3Exception.Protocol>(proxy).message.orEmpty())
    }

    @Test
    fun `a malformed authorization header names the region only when it is wrong`() {
        fun malformed(region: String?) = mapS3Error(
            S3ErrorContext(
                operation = S3Operation.Get,
                key = "key",
                statusCode = 400,
                error = S3ErrorBody("AuthorizationHeaderMalformed", "message", region, null),
                headers = Headers.Empty,
                now = testS3Time,
                signingRegion = REGION,
            ),
        )
        assertEquals("eu-west-1", assertIs<S3Exception.WrongRegion>(malformed("eu-west-1")).expectedRegion)
        assertIs<S3Exception.AuthenticationFailed>(malformed(REGION))
        assertIs<S3Exception.AuthenticationFailed>(malformed(null))
    }

    @Test
    fun `MinIO error code headers stand in for a missing body`() {
        val headers = headersOf("x-minio-error-code", "NoSuchBucket")
        val error = mapS3Error(context(404, headers = headers))
        assertIs<S3Exception.BucketNotFound>(error)
        assertEquals("NoSuchBucket", error.errorCode)
        assertIs<S3Exception.NotFound>(mapS3Error(context(404, "NoSuchKey", headers)))
    }

    @Test
    fun `region header wins but explicit skew server time wins over Date`() {
        val headers = headersOf(
            "x-amz-bucket-region" to listOf("header-region"),
            HttpHeaders.Date to listOf("Thu, 29 Feb 2024 23:59:58 GMT"),
        )
        val body = S3ErrorBody("PermanentRedirect", null, "body-region", null)
        val region = mapS3Error(S3ErrorContext(S3Operation.Get, "key", 301, body, headers, testS3Time, REGION))
        assertEquals("header-region", assertIs<S3Exception.WrongRegion>(region).expectedRegion)
        val skew = body.copy(code = "RequestTimeTooSkewed", serverTime = testS3Time + 30.minutes)
        val error = mapS3Error(S3ErrorContext(S3Operation.Get, "key", 403, skew, headers, testS3Time, REGION))
        assertEquals(skew.serverTime, assertIs<S3Exception.ClockSkew>(error).serverTime)
        assertNull(assertIs<S3Exception.ClockSkew>(mapS3Error(context(403, "RequestTimeTooSkewed"))).serverTime)
    }

    private fun context(status: Int, code: String? = null, headers: Headers = Headers.Empty) = S3ErrorContext(
        operation = S3Operation.Get,
        key = "key",
        statusCode = status,
        error = code?.let { S3ErrorBody(it, "message", null, null) },
        headers = headers,
        now = testS3Time,
        signingRegion = REGION,
    )

    private companion object {
        private const val REGION = "us-east-1"
    }
}
