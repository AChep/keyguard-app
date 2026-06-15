package com.artemchep.keyguard.common.usecase.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.exception.OtpInvalidSecretKeyException
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.totp.TotpService
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GetTotpCodeWithOffsetImplTest {
    @Test
    fun `invoke continues emitting successful refreshed values`() = runTest {
        val service = SequenceTotpService(
            results = ArrayDeque(
                listOf(
                    createCode("111111", TEST_INSTANT).right(),
                    createCode("222222", TEST_INSTANT).right(),
                ),
            ),
        )
        val usecase = GetTotpCodeWithOffsetImpl(service)

        val result = usecase(
            token = createTotpToken(),
            offset = 0,
        ).take(2).toList()

        assertEquals(2, result.size)
        assertEquals("111111", assertIs<Either.Right<TotpCode>>(result[0]).value.code)
        assertEquals("222222", assertIs<Either.Right<TotpCode>>(result[1]).value.code)
    }

    @Test
    fun `invoke emits Left and stops when zero offset generation fails`() = runTest {
        val service = SequenceTotpService(
            results = ArrayDeque(
                listOf(
                    OtpInvalidSecretKeyException().left(),
                ),
            ),
        )
        val usecase = GetTotpCodeWithOffsetImpl(service)

        val result = usecase(
            token = createTotpToken(),
            offset = 1,
        ).toList()

        assertEquals(1, result.size)
        val left = assertIs<Either.Left<Throwable>>(result.single())
        assertIs<OtpInvalidSecretKeyException>(left.value)
        assertEquals(1, service.calls.size)
        assertEquals(0, service.calls.single().offset)
    }
}

private class SequenceTotpService(
    private val results: ArrayDeque<Either<Throwable, TotpCode>>,
) : TotpService {
    data class Call(
        val offset: Int,
    )

    val calls = mutableListOf<Call>()

    override fun generate(
        token: TotpToken,
        timestamp: Instant,
        offset: Int,
    ): Either<Throwable, TotpCode> {
        calls += Call(offset = offset)
        return results.removeFirst()
    }
}

private fun createCode(
    code: String,
    timestamp: Instant,
) = TotpCode(
    code = code,
    counter = TotpCode.TimeBasedCounter(
        timestamp = timestamp,
        expiration = timestamp,
        duration = 30.seconds,
    ),
)

private fun createTotpToken() = TotpToken.TotpAuth(
    algorithm = CryptoHashAlgorithm.SHA_1,
    keyBase32 = "valid",
    raw = "valid",
    digits = 6,
    period = 30L,
)

private val TEST_INSTANT = Instant.fromEpochSeconds(1_700_000_000)
