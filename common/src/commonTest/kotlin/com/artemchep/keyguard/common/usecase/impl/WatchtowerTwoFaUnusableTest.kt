package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.test.TEST_INSTANT
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchtowerTwoFaUnusableTest {
    private val client = WatchtowerTwoFaUnusable()

    @Test
    fun `flags only a one-time password that Keyguard can not parse`() = runTest {
        val results = client.process(
            listOf(
                createSecret(
                    id = "unsupported",
                    login = DSecret.Login(totpUnsupported = UNSUPPORTED_TOTP),
                ),
                createSecret(
                    id = "none",
                    login = DSecret.Login(),
                ),
                createSecret(
                    id = "note",
                    type = DSecret.Type.SecureNote,
                ),
            ),
        )

        assertEquals(
            listOf(
                "unsupported" to true,
                "none" to false,
                "note" to false,
            ),
            results.map { it.cipher.id to it.threat },
        )
    }

    @Test
    fun `does not store the secret value`() = runTest {
        val result = client.process(
            listOf(
                createSecret(
                    id = "unsupported",
                    login = DSecret.Login(totpUnsupported = UNSUPPORTED_TOTP),
                ),
            ),
        ).single()

        assertEquals(true, result.threat)
        assertEquals(false, result.value?.contains("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `ignoring the alert clears it`() = runTest {
        val cipher = createSecret(
            id = "unsupported",
            login = DSecret.Login(totpUnsupported = UNSUPPORTED_TOTP),
            ignoredAlerts = mapOf(DWatchtowerAlertType.TWO_FA_UNUSABLE to TEST_INSTANT),
        )

        val result = client.process(listOf(cipher)).single()

        assertEquals(false, result.threat)
        assertEquals(null, result.value)
    }
}

private const val UNSUPPORTED_TOTP = "otpauth://totp/Example?secret=JBSWY3DPEHPK3PXP&digits=10"
