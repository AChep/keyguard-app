package com.artemchep.keyguard.ipctestclient.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.ipctestclient.ipc.ApprovalHost
import com.artemchep.keyguard.ipctestclient.ipc.IpcConnection
import com.artemchep.keyguard.ipctestclient.ipc.IpcExchange
import com.artemchep.keyguard.ipctestclient.ipc.IpcProviders
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpClient
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRunner
import com.artemchep.keyguard.ipctestclient.ipc.SshClient
import com.artemchep.keyguard.ipctestclient.ipc.SshRunner
import com.artemchep.keyguard.ipctestclient.ipc.describe
import com.artemchep.keyguard.ipctestclient.ipc.dumpExtras
import com.artemchep.keyguard.ipctestclient.ipc.formatOpenPgpResult
import com.artemchep.keyguard.ipctestclient.ipc.formatSshResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.ssh.authentication.ISshAuthenticationService
import java.io.Closeable

/**
 * Holds the driver's state and runs the IPC off the main thread.
 *
 * Nothing protocol-specific lives here: request building, result decoding and
 * the approval loop all come from the shared `ipc` package, which the
 * instrumentation suite uses unchanged.
 */
class DriverController(
    private val context: Context,
    private val approvals: ApprovalHost,
    private val scope: CoroutineScope,
) : Closeable {
    private val openPgpConnection = IpcConnection(
        context = context,
        action = IpcProviders.OPENPGP_SERVICE_ACTION,
        asInterface = IOpenPgpService2.Stub::asInterface,
    )
    private val sshConnection = IpcConnection(
        context = context,
        action = IpcProviders.SSH_SERVICE_ACTION,
        asInterface = ISshAuthenticationService.Stub::asInterface,
    )

    var connections by mutableStateOf(ConnectionState())
        private set
    var pgpForm by mutableStateOf(OpenPgpFormState())
    var sshForm by mutableStateOf(SshFormState())
    var scratch by mutableStateOf(Scratchpad())
        private set
    var busy by mutableStateOf(false)
        private set
    var report by mutableStateOf<Report?>(null)
        private set

    data class ConnectionState(
        val openPgp: IpcProviders.Provider? = null,
        val ssh: IpcProviders.Provider? = null,
        val openPgpBound: Boolean = false,
        val sshBound: Boolean = false,
        val legacyOpenPgpProviders: List<IpcProviders.Provider> = emptyList(),
    )

    data class Report(
        val title: String,
        val decoded: String,
        val raw: String,
        val trace: String,
        val output: ByteArray?,
    )

    fun refresh() {
        connections = ConnectionState(
            openPgp = IpcProviders.resolve(context, IpcProviders.OPENPGP_SERVICE_ACTION),
            ssh = IpcProviders.resolve(context, IpcProviders.SSH_SERVICE_ACTION),
            openPgpBound = openPgpConnection.isBound,
            sshBound = sshConnection.isBound,
            legacyOpenPgpProviders = IpcProviders.resolveAll(
                context,
                IpcProviders.LEGACY_OPENPGP_SERVICE_ACTION,
            ),
        )
    }

    fun runOpenPgp() = runOffMain("OpenPGP ${pgpForm.operation.label}") {
        val service = openPgpConnection.connect()
        val runner = OpenPgpRunner(OpenPgpClient(context, service), approvals)
        val exchange = runner.run(pgpForm.toSpec())
        val decoded = exchange.result
            ?.let { formatOpenPgpResult(it, exchange.output) }
            ?: "no result"
        scratch = scratch.updatedWithOpenPgp(exchange)
        exchange to decoded
    }

    fun runSsh() = runOffMain("SSH ${sshForm.operation.label}") {
        val service = sshConnection.connect()
        val runner = SshRunner(SshClient(context, service), approvals)
        val exchange = runner.run(sshForm.toSpec(scratch))
        val decoded = exchange.result?.let(::formatSshResult) ?: "no result"
        scratch = scratch.updatedWithSsh(exchange)
        exchange to decoded
    }

    fun unbind() {
        openPgpConnection.close()
        sshConnection.close()
        refresh()
    }

    fun clearScratchpad() {
        scratch = Scratchpad()
    }

    override fun close() = unbind()

    private fun runOffMain(
        title: String,
        block: suspend () -> Pair<IpcExchange, String>,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { block() } }
            report = outcome.fold(
                onSuccess = { (exchange, decoded) ->
                    Report(
                        title = title,
                        decoded = decoded,
                        raw = exchange.result?.dumpExtras() ?: "no result",
                        trace = exchange.describe(),
                        output = exchange.output,
                    )
                },
                onFailure = { error ->
                    Report(
                        title = title,
                        decoded = "${error.javaClass.simpleName}: ${error.message}",
                        raw = "",
                        trace = error.stackTraceToString(),
                        output = null,
                    )
                },
            )
            busy = false
            refresh()
        }
    }
}
