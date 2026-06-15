package com.artemchep.keyguard.wear.feature.auth

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthRequestState
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_method_keepass_wear_note
import com.artemchep.keyguard.res.addaccount_method_phone_importing
import com.artemchep.keyguard.res.addaccount_method_phone_unavailable
import com.artemchep.keyguard.res.addaccount_method_phone_waiting
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearLoginMethodStateTest {
    @Test
    fun `account type maps to matching companion provider`() {
        assertEquals(
            CompanionAuthProvider.BITWARDEN,
            companionAuthProviderOf(AccountType.BITWARDEN),
        )
        assertEquals(
            CompanionAuthProvider.KEEPASS,
            companionAuthProviderOf(AccountType.KEEPASS),
        )
    }

    @Test
    fun `picker phone login is disabled when no phone is available`() {
        assertFalse(
            shouldEnablePhoneLogin(
                isPhoneAvailable = false,
            ),
        )
    }

    @Test
    fun `picker phone login is enabled when phone is available`() {
        assertTrue(
            shouldEnablePhoneLogin(
                isPhoneAvailable = true,
            ),
        )
    }

    @Test
    fun `keepass picker shows info text and hides manual login`() {
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_keepass_wear_note),
            infoTextOf(AccountType.KEEPASS),
        )
        assertFalse(
            shouldShowManualLogin(AccountType.KEEPASS),
        )
    }

    @Test
    fun `bitwarden picker has no info text and keeps manual login`() {
        assertNull(
            infoTextOf(AccountType.BITWARDEN),
        )
        assertTrue(
            shouldShowManualLogin(AccountType.BITWARDEN),
        )
    }

    @Test
    fun `phone status text reflects availability and request state`() {
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_waiting),
            phoneStatusTextOf(
                isPhoneAvailable = true,
                requestState = CompanionAuthRequestState.WaitingForPhone,
            ),
        )
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_importing),
            phoneStatusTextOf(
                isPhoneAvailable = true,
                requestState = CompanionAuthRequestState.Importing,
            ),
        )
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_unavailable),
            phoneStatusTextOf(
                isPhoneAvailable = false,
                requestState = null,
            ),
        )
        assertEquals(
            TextHolder.Value("custom error"),
            phoneStatusTextOf(
                isPhoneAvailable = true,
                requestState = CompanionAuthRequestState.Failed(
                    error = com.artemchep.keyguard.feature.auth.companion.CompanionAuthError.UNKNOWN,
                    message = "custom error",
                ),
            ),
        )
    }
}
