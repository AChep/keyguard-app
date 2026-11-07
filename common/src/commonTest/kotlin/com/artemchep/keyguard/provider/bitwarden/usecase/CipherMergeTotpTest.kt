package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.test.createSecret
import kotlin.test.Test
import kotlin.test.assertEquals

class CipherMergeTotpTest {
    @Test
    fun `merge keeps a one-time password that Keyguard can not parse`() {
        val first = createSecret(
            id = "first",
            login = DSecret.Login(username = "user"),
        )
        val second = createSecret(
            id = "second",
            login = DSecret.Login(totpUnsupported = UNSUPPORTED_TOTP),
        )

        val merged = CipherMergeImpl()(listOf(first, second))

        assertEquals(UNSUPPORTED_TOTP, merged.login?.totpRaw)
    }
}

private const val UNSUPPORTED_TOTP = "otpauth://totp/Example?secret=JBSWY3DPEHPK3PXP&digits=10"
