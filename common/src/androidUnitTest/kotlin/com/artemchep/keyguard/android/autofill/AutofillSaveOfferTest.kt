package com.artemchep.keyguard.android.autofill

import com.artemchep.keyguard.common.model.AutofillHint
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutofillSaveOfferTest {
    private val login = listOf(AutofillHint.USERNAME, AutofillHint.PASSWORD)

    @Test
    fun `offers to save a login form`() {
        assertTrue(shouldOfferSave(saveRequests = true, ownApp = false, saveBlocked = false, hints = login))
    }

    @Test
    fun `a blocked uri that blocks saving stops the offer`() {
        assertFalse(shouldOfferSave(saveRequests = true, ownApp = false, saveBlocked = true, hints = login))
    }

    @Test
    fun `the setting and keyguard's own forms stop the offer`() {
        assertFalse(shouldOfferSave(saveRequests = false, ownApp = false, saveBlocked = false, hints = login))
        assertFalse(shouldOfferSave(saveRequests = true, ownApp = true, saveBlocked = false, hints = login))
    }

    @Test
    fun `a login form needs a username and a password`() {
        assertTrue(isLoginForm(listOf(AutofillHint.EMAIL_ADDRESS, AutofillHint.NEW_PASSWORD)))
        assertTrue(isLoginForm(listOf(AutofillHint.PHONE_NUMBER, AutofillHint.PASSWORD)))
        assertTrue(isLoginForm(listOf(AutofillHint.NEW_USERNAME, AutofillHint.PASSWORD)))
        assertFalse(isLoginForm(listOf(AutofillHint.PASSWORD)))
        assertFalse(isLoginForm(listOf(AutofillHint.USERNAME, AutofillHint.EMAIL_ADDRESS)))
        assertFalse(
            shouldOfferSave(
                saveRequests = true,
                ownApp = false,
                saveBlocked = false,
                hints = listOf(AutofillHint.PASSWORD),
            ),
        )
    }
}
