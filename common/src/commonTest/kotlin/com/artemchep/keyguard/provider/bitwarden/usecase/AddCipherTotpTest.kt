package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.test.TestCryptoGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class AddCipherTotpTest {
    @Test
    fun `editing an item keeps a one-time password that Keyguard can not parse`() = runTest {
        val old = loginCipher(totp = UNSUPPORTED_TOTP)
        val domain = old.toDomain(getPasswordStrength = fakeGetPasswordStrength)

        // The editor fills the field with the stored value
        // and saves it back as is.
        val login = BitwardenCipher.Login.of(
            cryptoGenerator = TestCryptoGenerator(),
            base64Service = Base64ServiceImpl(),
            getPasswordStrength = fakeGetPasswordStrength,
            now = TEST_INSTANT,
            old = old,
            _totp = domain.login?.totpRaw.orEmpty(),
        )

        assertEquals(UNSUPPORTED_TOTP, login.totp)
    }

    private fun loginCipher(totp: String) = BitwardenCipher(
        accountId = "account-1",
        cipherId = "cipher-1",
        revisionDate = TEST_INSTANT,
        createdDate = TEST_INSTANT,
        service = BitwardenService(),
        name = "Login",
        notes = "",
        favorite = false,
        reprompt = BitwardenCipher.RepromptType.None,
        type = BitwardenCipher.Type.Login,
        login = BitwardenCipher.Login(
            uris = emptyList(),
            totp = totp,
        ),
    )
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
private const val UNSUPPORTED_TOTP = "otpauth://totp/Example?secret=JBSWY3DPEHPK3PXP&digits=10"

private val fakeGetPasswordStrength = object : GetPasswordStrength {
    override fun invoke(password: String): IO<PasswordStrength> = ioEffect {
        error("unused in one-time password tests")
    }
}
