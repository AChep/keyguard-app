package com.artemchep.keyguard.ipctestclient.ipc

import android.content.Intent

/**
 * One logical operation and every binder call and approval it took.
 *
 * A single client operation is rarely a single round trip: an unregistered
 * caller is prompted to register, and a private-key action is then prompted to
 * authenticate, so two approvals before a result is normal.
 */
class IpcExchange(val legs: List<Leg>) {
    class Leg(
        val label: String,
        val request: Intent? = null,
        val result: Intent? = null,
        val output: ByteArray? = null,
        val approval: ApprovalOutcome? = null,
        val error: Throwable? = null,
        val durationMs: Long = 0L,
    )

    val last: Leg get() = legs.last()

    /** The last leg that actually reached the provider. */
    val lastCall: Leg? get() = legs.lastOrNull { it.result != null }

    val result: Intent? get() = lastCall?.result

    val output: ByteArray? get() = lastCall?.output

    val error: Throwable? get() = legs.firstNotNullOfOrNull { it.error }

    val approvalCount: Int get() = legs.count { it.approval != null }

    /**
     * Both APIs happen to name this extra `result_code`, so one accessor serves
     * OpenPGP and SSH alike.
     */
    val resultCode: Int
        get() = result?.getIntExtra(RESULT_CODE_EXTRA, UNKNOWN_RESULT_CODE)
            ?: UNKNOWN_RESULT_CODE

    companion object {
        const val RESULT_CODE_EXTRA = "result_code"
        const val UNKNOWN_RESULT_CODE = Int.MIN_VALUE
    }
}
