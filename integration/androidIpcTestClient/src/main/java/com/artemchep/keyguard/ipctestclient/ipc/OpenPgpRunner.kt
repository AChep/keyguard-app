package com.artemchep.keyguard.ipctestclient.ipc

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
    ): IpcExchange = runApprovalExchange(
        initial = spec.toIntent(),
        approvals = approvals,
        maxApprovals = maxApprovals,
        intentExtra = OpenPgpApi.RESULT_INTENT,
    ) { request -> call(spec, request) }

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
}
