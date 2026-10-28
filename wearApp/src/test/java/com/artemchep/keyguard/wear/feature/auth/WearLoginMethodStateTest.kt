package com.artemchep.keyguard.wear.feature.auth

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthError
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneDevice
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneDiscovery
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthRequestState
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_method_keepass_wear_note
import com.artemchep.keyguard.res.addaccount_method_phone_cancelled
import com.artemchep.keyguard.res.addaccount_method_phone_checking
import com.artemchep.keyguard.res.addaccount_method_phone_error_timeout
import com.artemchep.keyguard.res.addaccount_method_phone_error_unreachable
import com.artemchep.keyguard.res.addaccount_method_phone_failed
import com.artemchep.keyguard.res.addaccount_method_phone_importing
import com.artemchep.keyguard.res.addaccount_method_phone_services_unavailable
import com.artemchep.keyguard.res.addaccount_method_phone_unavailable
import com.artemchep.keyguard.res.addaccount_method_phone_waiting
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WearLoginMethodStateTest {
    private val phone = CompanionAuthPhoneDevice("phone", "Phone")

    @Test
    fun `manual login is usable while discovery is suspended`() = runTest {
        var manualLoginCount = 0
        val state = wearLoginMethodStateFlow(
            accountType = AccountType.BITWARDEN,
            phoneDiscoveryFlow = flow {
                emit(CompanionAuthPhoneDiscovery.Loading)
                awaitCancellation()
            },
            onPhoneLoginClick = {},
            onManualLoginClick = { manualLoginCount++ },
        ).first()

        assertTrue(state.isManualLoginVisible)
        requireNotNull(state.onManualLoginClick).invoke()
        assertEquals(1, manualLoginCount)
        assertFalse(state.isPhoneLoginEnabled)
        assertEquals(TextHolder.Res(Res.string.addaccount_method_phone_checking), state.phoneStatusText)
    }

    @Test
    fun `unavailable services leave manual login usable and explain phone failure`() = runTest {
        var manualLoginCount = 0
        val state = wearLoginMethodStateFlow(
            accountType = AccountType.BITWARDEN,
            phoneDiscoveryFlow = flowOf(CompanionAuthPhoneDiscovery.Unavailable),
            onPhoneLoginClick = {},
            onManualLoginClick = { manualLoginCount++ },
        ).toList().last()

        requireNotNull(state.onManualLoginClick).invoke()
        assertEquals(1, manualLoginCount)
        assertFalse(state.isPhoneLoginEnabled)
        assertNull(state.onPhoneLoginClick)
        assertEquals(TextHolder.Res(Res.string.addaccount_method_phone_services_unavailable), state.phoneStatusText)
    }

    @Test
    fun `keepass has no manual action while discovery loads or fails`() = runTest {
        val states = wearLoginMethodStateFlow(
            accountType = AccountType.KEEPASS,
            phoneDiscoveryFlow = flowOf(
                CompanionAuthPhoneDiscovery.Loading,
                CompanionAuthPhoneDiscovery.Unavailable,
            ),
            onPhoneLoginClick = {},
            onManualLoginClick = { error("KeePass cannot use manual login") },
        ).toList()

        states.forEach { state ->
            assertFalse(state.isManualLoginVisible)
            assertNull(state.onManualLoginClick)
            assertEquals(TextHolder.Res(Res.string.addaccount_method_keepass_wear_note), state.infoText)
        }
    }

    @Test
    fun `phone connectivity updates preserve manual login`() = runTest {
        val states = wearLoginMethodStateFlow(
            accountType = AccountType.BITWARDEN,
            phoneDiscoveryFlow = flowOf(
                CompanionAuthPhoneDiscovery.Ready(listOf(phone)),
                CompanionAuthPhoneDiscovery.Ready(emptyList()),
            ),
            onPhoneLoginClick = {},
            onManualLoginClick = {},
        ).toList()

        assertEquals(listOf(true, false), states.map { it.isPhoneLoginEnabled })
        assertTrue(states.all { it.onManualLoginClick != null })
        assertEquals(TextHolder.Res(Res.string.addaccount_method_phone_unavailable), states.last().phoneStatusText)
    }

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
                phoneDiscovery = CompanionAuthPhoneDiscovery.Ready(listOf(phone)),
                requestState = CompanionAuthRequestState.WaitingForPhone,
            ),
        )
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_importing),
            phoneStatusTextOf(
                phoneDiscovery = CompanionAuthPhoneDiscovery.Ready(listOf(phone)),
                requestState = CompanionAuthRequestState.Importing,
            ),
        )
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_unavailable),
            phoneStatusTextOf(
                phoneDiscovery = CompanionAuthPhoneDiscovery.Ready(emptyList()),
                requestState = null,
            ),
        )
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_error_timeout),
            phoneStatusTextOf(
                phoneDiscovery = CompanionAuthPhoneDiscovery.Ready(listOf(phone)),
                requestState = CompanionAuthRequestState.Failed(
                    error = CompanionAuthError.TIMEOUT,
                ),
            ),
        )
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_cancelled),
            phoneStatusTextOf(
                phoneDiscovery = CompanionAuthPhoneDiscovery.Ready(listOf(phone)),
                requestState = CompanionAuthRequestState.Cancelled,
            ),
        )
    }

    @Test
    fun `every error has a localized text`() {
        CompanionAuthError.entries.forEach { error ->
            assertIs<TextHolder.Res>(phoneErrorTextOf(error))
        }
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_error_unreachable),
            phoneErrorTextOf(CompanionAuthError.PHONE_UNAVAILABLE),
        )
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_failed),
            phoneErrorTextOf(CompanionAuthError.UNKNOWN),
        )
    }
}
