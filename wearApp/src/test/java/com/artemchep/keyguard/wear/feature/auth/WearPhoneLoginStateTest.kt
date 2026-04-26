package com.artemchep.keyguard.wear.feature.auth

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthError
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthRequestState
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_method_phone_pick
import com.artemchep.keyguard.res.retry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WearPhoneLoginStateTest {
    @Test
    fun `launch behavior depends on number of reachable phones`() {
        assertEquals(
            WearPhoneLoginLaunchBehavior.NONE,
            phoneLoginLaunchBehaviorOf(phoneDeviceCount = 0),
        )
        assertEquals(
            WearPhoneLoginLaunchBehavior.AUTO_START,
            phoneLoginLaunchBehaviorOf(phoneDeviceCount = 1),
        )
        assertEquals(
            WearPhoneLoginLaunchBehavior.PICK_DEVICE,
            phoneLoginLaunchBehaviorOf(phoneDeviceCount = 2),
        )
    }

    @Test
    fun `action is disabled while waiting or importing`() {
        assertFalse(
            shouldEnablePhoneLoginAction(
                phoneDeviceCount = 1,
                requestState = CompanionAuthRequestState.WaitingForPhone,
            ),
        )
        assertFalse(
            shouldEnablePhoneLoginAction(
                phoneDeviceCount = 2,
                requestState = CompanionAuthRequestState.Importing,
            ),
        )
    }

    @Test
    fun `action is enabled when request failed or cancelled`() {
        assertTrue(
            shouldEnablePhoneLoginAction(
                phoneDeviceCount = 1,
                requestState = CompanionAuthRequestState.Failed(
                    error = CompanionAuthError.UNKNOWN,
                ),
            ),
        )
        assertTrue(
            shouldEnablePhoneLoginAction(
                phoneDeviceCount = 2,
                requestState = CompanionAuthRequestState.Cancelled(),
            ),
        )
    }

    @Test
    fun `action is enabled for multi-device picker when no request is active`() {
        assertTrue(
            shouldEnablePhoneLoginAction(
                phoneDeviceCount = 2,
                requestState = null,
            ),
        )
        assertFalse(
            shouldEnablePhoneLoginAction(
                phoneDeviceCount = 1,
                requestState = null,
            ),
        )
    }

    @Test
    fun `action is disabled when phone is unavailable or request is not actionable`() {
        assertFalse(
            shouldEnablePhoneLoginAction(
                phoneDeviceCount = 0,
                requestState = CompanionAuthRequestState.Failed(
                    error = CompanionAuthError.UNKNOWN,
                ),
            ),
        )
        assertFalse(
            shouldEnablePhoneLoginAction(
                phoneDeviceCount = 1,
                requestState = CompanionAuthRequestState.Success,
            ),
        )
    }

    @Test
    fun `progress is shown while waiting or importing`() {
        assertTrue(
            shouldShowPhoneLoginProgress(
                requestState = CompanionAuthRequestState.WaitingForPhone,
            ),
        )
        assertTrue(
            shouldShowPhoneLoginProgress(
                requestState = CompanionAuthRequestState.Importing,
            ),
        )
        assertFalse(
            shouldShowPhoneLoginProgress(
                requestState = CompanionAuthRequestState.Failed(
                    error = CompanionAuthError.UNKNOWN,
                ),
            ),
        )
    }

    @Test
    fun `action text switches between picking and retrying`() {
        assertEquals(
            TextHolder.Res(Res.string.addaccount_method_phone_pick),
            phoneLoginActionTextOf(phoneDeviceCount = 2),
        )
        assertEquals(
            TextHolder.Res(Res.string.retry),
            phoneLoginActionTextOf(phoneDeviceCount = 1),
        )
    }
}
