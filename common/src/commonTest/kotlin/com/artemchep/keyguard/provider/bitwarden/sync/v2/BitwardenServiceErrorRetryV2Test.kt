package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.canRetry
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class BitwardenServiceErrorRetryV2Test {
    @Test
    fun `transient error codes are retryable`() {
        val retryableCodes = listOf(
            BitwardenService.Error.CODE_UNKNOWN,
            BitwardenService.Error.CODE_PROTOCOL_EXCEPTION,
            BitwardenService.Error.CODE_UNKNOWN_HOST,
            BitwardenService.Error.CODE_DECODING_FAILED,
            HttpStatusCode.TooManyRequests.value,
            HttpStatusCode.InternalServerError.value,
            HttpStatusCode.BadGateway.value,
        )

        retryableCodes.forEach { code ->
            assertEquals(
                true,
                error(code = code).canRetry(revisionDate = T1),
                "Expected $code to be retryable",
            )
        }
    }

    @Test
    fun `auth and bad request error codes are not retryable`() {
        val nonRetryableCodes = listOf(
            HttpStatusCode.Unauthorized.value,
            HttpStatusCode.BadRequest.value,
        )

        nonRetryableCodes.forEach { code ->
            assertEquals(
                false,
                error(code = code).canRetry(revisionDate = T1),
                "Expected $code to be non-retryable",
            )
        }
    }

    @Test
    fun `expired error is retryable regardless of code`() {
        assertEquals(
            true,
            error(code = HttpStatusCode.Unauthorized.value, revisionDate = T0).canRetry(revisionDate = T1),
        )
    }

    private fun error(
        code: Int,
        revisionDate: kotlin.time.Instant = T1,
    ) = BitwardenService.Error(
        code = code,
        revisionDate = revisionDate,
    )
}
