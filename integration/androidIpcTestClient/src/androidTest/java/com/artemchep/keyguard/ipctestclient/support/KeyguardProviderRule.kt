package com.artemchep.keyguard.ipctestclient.support

import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.artemchep.keyguard.ipctestclient.IpcTestClientActivity
import com.artemchep.keyguard.ipctestclient.ipc.IpcConnection
import com.artemchep.keyguard.ipctestclient.ipc.IpcProviders
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpClient
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRunner
import com.artemchep.keyguard.ipctestclient.ipc.SshClient
import com.artemchep.keyguard.ipctestclient.ipc.SshRunner
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.ssh.authentication.ISshAuthenticationService

/**
 * Binds the Keyguard providers for one test class.
 *
 * A provider that is switched off in Keyguard does not publish its component at
 * all, so the bind is skipped with an assumption rather than failed: that is a
 * fixture fact, not a contract violation.
 */
class KeyguardProviderRule : ExternalResource() {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    val clientPackage: String get() = context.packageName

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

    fun openPgpService(): IOpenPgpService2 {
        assumeTrue(
            "The OpenPGP provider is not published; enable it in Keyguard.",
            openPgpConnection.provider() != null,
        )
        return openPgpConnection.connect()
    }

    fun sshService(): ISshAuthenticationService {
        assumeTrue(
            "The SSH Authentication provider is not published; enable it in Keyguard.",
            sshConnection.provider() != null,
        )
        return sshConnection.connect()
    }

    fun openPgpClient(): OpenPgpClient = OpenPgpClient(context, openPgpService())

    fun sshClient(): SshClient = SshClient(context, sshService())

    fun disconnectOpenPgp() {
        openPgpConnection.close()
    }

    fun disconnectSsh() {
        sshConnection.close()
    }

    fun openPgpComponent(): ComponentName = requireProvider(
        connection = openPgpConnection,
        label = "OpenPGP",
    )

    fun sshComponent(): ComponentName = requireProvider(
        connection = sshConnection,
        label = "SSH Authentication",
    )

    /** Waits until Android has actually destroyed an unbound target service. */
    fun awaitServiceStopped(
        component: ComponentName,
        timeoutMs: Long = SERVICE_STOP_TIMEOUT_MS,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastDump = ""
        do {
            lastDump = instrumentation.uiAutomation
                .executeShellCommand(
                    "dumpsys activity services ${component.flattenToString()}",
                )
                .let(ParcelFileDescriptor::AutoCloseInputStream)
                .bufferedReader()
                .use { it.readText() }
            val isRunning = lastDump.lineSequence().any { line ->
                if (!line.contains("ServiceRecord{")) return@any false
                line.contains(component.className) ||
                        line.contains(component.flattenToShortString())
            }
            if (!isRunning) {
                return
            }
            SystemClock.sleep(SERVICE_STOP_POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(
            "${component.flattenToShortString()} remained active after unbind:\n$lastDump",
        )
    }

    fun openPgpRunner(
        action: ApprovalRobot.Action = ApprovalRobot.Action.APPROVE,
    ): OpenPgpRunner = OpenPgpRunner(openPgpClient(), robot().host(action))

    fun sshRunner(
        action: ApprovalRobot.Action = ApprovalRobot.Action.APPROVE,
    ): SshRunner = SshRunner(sshClient(), robot().host(action))

    fun robot(): ApprovalRobot = ApprovalRobot(
        clientPackage = clientPackage,
        keyguardPackage = keyguardPackage(),
    )

    /** The Keyguard build under test, whichever protocol resolves it. */
    fun keyguardPackage(): String {
        val provider = openPgpConnection.provider() ?: sshConnection.provider()
        assumeTrue("No Keyguard IPC provider is published.", provider != null)
        return provider!!.component.packageName
    }

    private fun <T : Any> requireProvider(
        connection: IpcConnection<T>,
        label: String,
    ): ComponentName {
        val provider = connection.provider()
        assumeTrue("The $label provider is not published; enable it in Keyguard.", provider != null)
        return provider!!.component
    }

    override fun after() {
        openPgpConnection.close()
        sshConnection.close()
        IpcTestClientActivity.results.clear()
    }

    private companion object {
        const val SERVICE_STOP_TIMEOUT_MS = 10_000L
        const val SERVICE_STOP_POLL_MS = 100L
    }
}
