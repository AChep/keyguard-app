package com.artemchep.keyguard.ipctestclient.ipc

import android.app.PendingIntent
import android.content.Intent
import org.openintents.openpgp.util.OpenPgpApi

/**
 * Drives one OpenPGP operation to a terminal result, handling the approval
 * round trips on the way.
 *
 * The retry intent is replayed **verbatim**. The provider's grant is bound to a
 * digest over the request's canonicalized extras, so editing any of them - even
 * to a value the client believes is equivalent - invalidates the token. Only the
 * streams are fresh: the input has already been consumed and the output pipe has
 * already been taken.
 */
class OpenPgpRunner(
    private val client: OpenPgpClient,
    private val approvals: ApprovalHost,
) {
    /** Sends the request once, leaving any approval request unanswered. */
    fun runOnce(spec: OpenPgpRequestSpec): IpcExchange =
        IpcExchange(listOf(call(spec, spec.toIntent()))).recorded()

    /** Sends [intent] with [spec]'s streams, for replaying or mutating a retry. */
    fun runIntent(spec: OpenPgpRequestSpec, intent: Intent): IpcExchange =
        IpcExchange(listOf(call(spec, intent))).recorded()

    fun run(
        spec: OpenPgpRequestSpec,
        maxApprovals: Int = DEFAULT_MAX_APPROVALS,
    ): IpcExchange {
        val legs = mutableListOf<IpcExchange.Leg>()
        var request: Intent? = spec.toIntent()
        var approvalsUsed = 0
        while (request != null) {
            val leg = call(spec, request)
            legs += leg
            val pendingIntent = leg
                .interactionPendingIntent(OpenPgpApi.RESULT_INTENT)
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

    private fun call(spec: OpenPgpRequestSpec, request: Intent): IpcExchange.Leg {
        val label = request.action?.substringAfterLast('.') ?: "<no action>"
        return runCatching {
            client.execute(
                request = request,
                input = spec.input(),
                outputMode = spec.outputMode(),
                outputPipeIdOverride = spec.outputPipeIdOverride,
            )
        }.fold(
            onSuccess = { result ->
                IpcExchange.Leg(
                    label = label,
                    request = request,
                    result = result.result,
                    output = result.output,
                    durationMs = result.durationMs,
                )
            },
            onFailure = { error ->
                IpcExchange.Leg(label = label, request = request, error = error)
            },
        )
    }

    companion object {
        /**
         * Registration and authentication are separate prompts, so one logical
         * call can legitimately need two approvals before it succeeds.
         */
        const val DEFAULT_MAX_APPROVALS = 2
    }
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
