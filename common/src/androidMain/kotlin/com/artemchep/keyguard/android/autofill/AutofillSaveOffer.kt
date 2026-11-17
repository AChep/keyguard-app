package com.artemchep.keyguard.android.autofill

import com.artemchep.keyguard.common.model.AutofillHint

/** Whether the [hints] of a form hold a username and a password: a login that Keyguard saves. */
internal fun isLoginForm(hints: Collection<AutofillHint>): Boolean {
    val hasUsername = AutofillHint.USERNAME in hints ||
        AutofillHint.NEW_USERNAME in hints ||
        AutofillHint.PHONE_NUMBER in hints ||
        AutofillHint.EMAIL_ADDRESS in hints
    val hasPassword = AutofillHint.PASSWORD in hints ||
        AutofillHint.NEW_PASSWORD in hints
    return hasUsername && hasPassword
}

/**
 * Whether a fill response asks Android to offer saving the login of a form
 * with the [hints]. Its datasets fill either way.
 *
 * @param saveRequests The user's setting that offers to save logins.
 * @param ownApp The form is Keyguard's own.
 * @param saveBlocked A blocked URI stops saving in the app or on the website of the form.
 */
internal fun shouldOfferSave(
    saveRequests: Boolean,
    ownApp: Boolean,
    saveBlocked: Boolean,
    hints: Collection<AutofillHint>,
): Boolean = saveRequests && !ownApp && !saveBlocked && isLoginForm(hints)
