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
        maxApprovals: Int = DEFAULT_MAX_APPROVALS,
    ): IpcExchange = runApprovalExchange(
        initial = spec.toIntent(),
        approvals = approvals,
        maxApprovals = maxApprovals,
        intentExtra = SshAuthenticationApi.EXTRA_PENDING_INTENT,
        call = ::call,
    )

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
