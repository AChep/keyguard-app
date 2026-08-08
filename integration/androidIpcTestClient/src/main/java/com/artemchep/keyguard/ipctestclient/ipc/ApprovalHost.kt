package com.artemchep.keyguard.ipctestclient.ipc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent

/**
 * Keyguard's private extra carrying the one-shot approval token.
 *
 * Not part of the OpenPGP or SSH API. It rides on the retry intent the approval
 * activity returns, is bound to the caller's uid, pid, signer, protocol, action
 * and request digest, and is consumed on first use. A client that edits any
 * extra of the retry intent invalidates it.
 */
const val ONE_SHOT_AUTHORIZATION_EXTRA =
    "com.artemchep.keyguard.android.ipc.extra.ONE_SHOT_AUTHORIZATION"

data class ApprovalOutcome(
    val resultCode: Int,
    val data: Intent?,
) {
    val approved: Boolean get() = resultCode == Activity.RESULT_OK && data != null

    /** The request to replay verbatim; null when the user denied or dismissed. */
    val retryIntent: Intent? get() = data.takeIf { approved }

    val hasAuthorizationToken: Boolean
        get() = data?.hasExtra(ONE_SHOT_AUTHORIZATION_EXTRA) == true

    val authorizationToken: String?
        get() = data?.getStringExtra(ONE_SHOT_AUTHORIZATION_EXTRA)
}

/**
 * Starts a provider approval [PendingIntent] and waits for its result.
 *
 * The driver implements this with an activity result launcher; the
 * instrumentation suite implements it with UI Automator so the dialog is
 * actually tapped.
 */
interface ApprovalHost {
    fun launch(
        pendingIntent: PendingIntent,
        timeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS,
    ): ApprovalOutcome

    companion object {
        const val DEFAULT_APPROVAL_TIMEOUT_MS = 60_000L
    }
}

/** Refuses every approval, for probing what a request does without one. */
object DeclineApprovalHost : ApprovalHost {
    override fun launch(pendingIntent: PendingIntent, timeoutMs: Long): ApprovalOutcome =
        ApprovalOutcome(Activity.RESULT_CANCELED, null)
}
