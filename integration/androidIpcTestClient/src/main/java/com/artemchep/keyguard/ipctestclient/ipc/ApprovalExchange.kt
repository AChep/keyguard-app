package com.artemchep.keyguard.ipctestclient.ipc

import android.app.PendingIntent
import android.content.Intent
import org.openintents.openpgp.util.OpenPgpApi

/**
 * Registration and authentication are separate prompts, so one logical
 * call can legitimately need two approvals before it succeeds.
 */
const val DEFAULT_MAX_APPROVALS = 2

/**
 * Sends [initial] through [call], launching each approval the provider asks
 * for and replaying the returned retry intent verbatim, up to [maxApprovals]
 * times.
 */
internal fun runApprovalExchange(
    initial: Intent,
    approvals: ApprovalHost,
    maxApprovals: Int,
    intentExtra: String,
    call: (Intent) -> IpcExchange.Leg,
): IpcExchange {
    val legs = mutableListOf<IpcExchange.Leg>()
    var request: Intent? = initial
    var approvalsUsed = 0
    while (request != null) {
        val leg = call(request)
        legs += leg
        val pendingIntent = leg
            .interactionPendingIntent(intentExtra)
            ?.takeIf { approvalsUsed < maxApprovals }
        request = if (pendingIntent == null) {
            null
        } else {
            approvalsUsed++
            val outcome = approvals.launch(pendingIntent)
            legs += IpcExchange.Leg(
                label = "approval #$approvalsUsed",
                approval = outcome,
            )
            outcome.retryIntent
        }
    }
    return IpcExchange(legs).recorded()
}

@Suppress("DEPRECATION")
fun Intent?.pendingIntentExtra(name: String): PendingIntent? =
    this?.getParcelableExtra(name)

/**
 * The approval [PendingIntent] this leg is asking for, if any. Both APIs use the
 * same `result_code` extra and the same value 2 for "user interaction required".
 */
fun IpcExchange.Leg.interactionPendingIntent(
    intentExtra: String,
): PendingIntent? {
    val code = result?.getIntExtra(
        IpcExchange.RESULT_CODE_EXTRA,
        IpcExchange.UNKNOWN_RESULT_CODE,
    )
    return result
        .takeIf {
            error == null &&
                code == OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED
        }
        .pendingIntentExtra(intentExtra)
}
