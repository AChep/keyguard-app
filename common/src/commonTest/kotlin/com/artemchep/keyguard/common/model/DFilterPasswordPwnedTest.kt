package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct

class DFilterPasswordPwnedTest {
    @Test
    fun `pwned password filter includes every positive occurrence count`() = runTest {
        val safe = secret(id = "safe", password = "safe-password")
        val pwnedOnce = secret(id = "pwned-once", password = "pwned-once-password")
        val pwnedTwice = secret(id = "pwned-twice", password = "pwned-twice-password")
        val ciphers = listOf(safe, pwnedOnce, pwnedTwice)
        val checkPasswordSetLeak = TestCheckPasswordSetLeak(
            occurrences = mapOf(
                "safe-password" to 0,
                "pwned-once-password" to 1,
                "pwned-twice-password" to 2,
            ),
        )
        val directDI = DI {
            bindSingleton<CheckPasswordSetLeak> {
                checkPasswordSetLeak
            }
        }.direct

        val predicate = DFilter.ByPasswordPwned.prepare(
            directDI = directDI,
            ciphers = ciphers,
        )

        assertFalse(predicate(safe))
        assertTrue(predicate(pwnedOnce))
        assertTrue(predicate(pwnedTwice))
        assertEquals(2, DFilter.ByPasswordPwned.count(directDI, ciphers))
    }
}

private fun secret(
    id: String,
    password: String,
) = createSecret(
    id = id,
    login = DSecret.Login(password = password),
)

private class TestCheckPasswordSetLeak(
    private val occurrences: Map<String, Int>,
) : CheckPasswordSetLeak {
    override fun invoke(
        request: CheckPasswordSetLeakRequest,
    ): IO<Map<String, PasswordPwnage?>> = io(
        request.passwords.associateWith { password ->
            occurrences[password]?.let(::PasswordPwnage)
        },
    )
}
