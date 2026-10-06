package com.artemchep.keyguard.android

import arrow.core.Either
import arrow.core.right
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AutofillDatasetRefreshTest {
    @Test
    fun `refresh after verification returns the current OTP and other fields`() = runTest {
        val token = TotpToken.parse("JBSWY3DPEHPK3PXP").getOrNull()!!
        val cipher = createSecret(
            id = "cipher",
            reprompt = true,
            login = DSecret.Login(
                username = "user",
                password = "password",
                totp = DSecret.Login.Totp(raw = token.raw, token = token),
            ),
        )
        val getCiphers = ciphers(
            cipher.copy(accountId = "other-account", login = null),
            cipher,
        )
        var now = Instant.parse("2026-10-03T00:00:00Z")
        val initialTime = now
        val getTotpCode = object : GetTotpCode {
            override fun invoke(token: TotpToken): Flow<Either<Throwable, TotpCode>> {
                val code = if (now == initialTime) "111111" else "222222"
                return flowOf(
                    TotpCode(
                        code = code,
                        counter = TotpCode.TimeBasedCounter(
                            timestamp = now,
                            expiration = now + 30.seconds,
                            duration = 30.seconds,
                        ),
                    ).right(),
                )
            }
        }
        val hints = setOf(AutofillHint.USERNAME, AutofillHint.PASSWORD, AutofillHint.APP_OTP)
        val beforeVerification = loadAutofillFields(
            cipher.accountId, cipher.id, hints, getCiphers, getTotpCode,
        )

        // The user spends longer than the original code's validity verifying.
        now += 60.seconds
        val afterVerification = loadAutofillFields(
            cipher.accountId, cipher.id, hints, getCiphers, getTotpCode,
        )

        assertEquals("111111", beforeVerification?.get(AutofillHint.APP_OTP))
        assertEquals(
            mapOf(
                AutofillHint.USERNAME to "user",
                AutofillHint.PASSWORD to "password",
                AutofillHint.APP_OTP to "222222",
            ),
            afterVerification,
        )
    }

    @Test
    fun `missing cipher does not fill data from another account`() = runTest {
        val getTotpCode = object : GetTotpCode {
            override fun invoke(token: TotpToken): Flow<Either<Throwable, TotpCode>> =
                error("OTP must not be generated for a missing cipher")
        }

        val fields = loadAutofillFields(
            accountId = "selected-account",
            cipherId = "cipher",
            hints = setOf(AutofillHint.APP_OTP),
            getCiphers = ciphers(createSecret(id = "cipher", accountId = "other-account")),
            getTotpCode = getTotpCode,
        )

        assertNull(fields)
    }

    private fun ciphers(vararg ciphers: DSecret) = object : GetCiphers {
        override fun invoke(): Flow<List<DSecret>> = flowOf(ciphers.toList())
    }
}
