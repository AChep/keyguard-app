package com.artemchep.keyguard.ipctestclient.ipc

import android.content.Intent
import org.openintents.ssh.authentication.SshAuthenticationApi

/** The SSH counterpart of [OpenPgpRunner]; same verbatim-retry contract. */
class SshRunner(
    private val client: SshClient,
    private val approvals: ApprovalHost,
) {
    fun runOnce(spec: SshRequestSpec): IpcExchange =
        IpcExchange(listOf(call(spec.toIntent()))).recorded()

    fun runIntent(intent: Intent): IpcExchange =
        IpcExchange(listOf(call(intent))).recorded()

    fun run(
        spec: SshRequestSpec,
        maxApprovals: Int = OpenPgpRunner.DEFAULT_MAX_APPROVALS,
    ): IpcExchange {
        val legs = mutableListOf<IpcExchange.Leg>()
        var request: Intent? = spec.toIntent()
        var approvalsUsed = 0
        while (request != null) {
            val leg = call(request)
            legs += leg
            val pendingIntent = leg
                .interactionPendingIntent(SshAuthenticationApi.EXTRA_PENDING_INTENT)
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

    private fun call(request: Intent): IpcExchange.Leg {
        val label = request.action?.substringAfterLast('.') ?: "<no action>"
        return runCatching { client.execute(request) }.fold(
            onSuccess = { result ->
                IpcExchange.Leg(
                    label = label,
                    request = request,
                    result = result.result,
                    durationMs = result.durationMs,
                )
            },
            onFailure = { error ->
                IpcExchange.Leg(label = label, request = request, error = error)
            },
        )
    }
}
