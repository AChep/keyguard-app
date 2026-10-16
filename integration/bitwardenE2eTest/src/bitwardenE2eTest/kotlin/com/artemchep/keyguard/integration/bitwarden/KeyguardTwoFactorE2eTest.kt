package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIsNot
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives Keyguard's login through the authenticator two-factor of a real server.
 *
 * Every test enables TOTP on its own account: enabling uses up the current TOTP step
 * and the server accepts each step once, see [VaultwardenTwoFactor].
 */
class KeyguardTwoFactorE2eTest {
    @Test
    fun `login without a code reports the authenticator provider`() = runE2eTest {
        val (account, _) = registerWithTotp()

        val failure = assertFailsWith<ApiException> { keyguardLogin(account) }
        assertEquals(HttpStatusCode.BadRequest, failure.code)
        assertEquals(TWO_FA_REQUIRED_AUTHENTICATOR, failure.type)
    }

    @Test
    fun `wrong code is rejected without a provider list`() = runE2eTest {
        val (account, _) = registerWithTotp()

        val failure = assertFailsWith<ApiException> {
            keyguardLogin(account, ServerTwoFactorToken("000000", TwoFactorProviderType.Authenticator))
        }
        assertEquals(HttpStatusCode.BadRequest, failure.code)
        assertIsNot<ApiException.Type.TwoFaRequired>(failure.type)
        assertContains(failure.message.orEmpty(), "Invalid TOTP code")
    }

    @Test
    fun `keyguard logs in with a totp code`() = runE2eTest {
        val (account, secret) = registerWithTotp()

        val session = keyguardLogin(
            account = account,
            twoFactorToken = ServerTwoFactorToken(
                token = twoFactor.totpCode(secret, stepOffset = 1),
                provider = TwoFactorProviderType.Authenticator,
                remember = true,
            ),
        )
        assertContentEquals(account.masterKey, session.secrets.masterKey)
        val profile = keyguardSync(session).profile
        assertEquals(account.email, profile.email)
        assertTrue(profile.twoFactorEnabled)
    }

    @Test
    fun `keyguard logs in with a remember token`() = runE2eTest {
        val (account, secret) = registerWithTotp()
        // Keyguard drops the remember token a login returns, so a Bitwarden client
        // fetches one for Keyguard's device; the server binds the token to the device.
        val remembered = accounts.login(
            account = account,
            twoFactor = RawTwoFactor(
                provider = RawTwoFactor.AUTHENTICATOR,
                token = twoFactor.totpCode(secret, stepOffset = 1),
                remember = true,
            ),
            deviceIdentifier = deviceId(),
        )
        val rememberToken = assertNotNull(remembered.twoFactorToken)

        val session = keyguardLogin(
            account = account,
            twoFactorToken = ServerTwoFactorToken(rememberToken, TwoFactorProviderType.Remember),
        )
        assertContentEquals(account.masterKey, session.secrets.masterKey)
        assertEquals(account.email, keyguardSync(session).profile.email)

        val withoutToken = assertFailsWith<ApiException> { keyguardLogin(account) }
        assertEquals(TWO_FA_REQUIRED_AUTHENTICATOR, withoutToken.type)
    }

    /** An account with the authenticator enabled; enabling used up the current TOTP step. */
    private data class TotpAccount(
        val account: TestAccount,
        val secret: String,
    )

    private suspend fun VaultwardenFixture.registerWithTotp(): TotpAccount {
        val account = accounts.register()
        val accessToken = accounts.login(account).accessToken
        val secret = twoFactor.enableTotp(account, accessToken)
        return TotpAccount(account, secret)
    }

    companion object {
        private val TWO_FA_REQUIRED_AUTHENTICATOR = ApiException.Type.TwoFaRequired(
            providers = listOf(TwoFactorProviderArgument.Authenticator),
        )
    }
}
