package com.artemchep.keyguard

import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSPipe
import platform.Foundation.NSTask
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.posix.getpid

@OptIn(ExperimentalForeignApi::class)
class SshAgentManagerApple(
    private val authToken: ByteArray,
    private val ipcServer: SshAgentIpcServerApple,
    private val log: (String) -> Unit = {},
) {
    companion object {
        val isBinaryAvailable: Boolean
            get() = findBundledBinary() != null

        val defaultSshAuthSockPath: String
            get() = appleAppGroupContainerPath().trimEnd('/') + "/ssh-agent.sock"

        private fun findBundledBinary(): String? {
            val bundle = NSBundle.mainBundle
            return bundle.pathForResource("keyguard-ssh-agent", ofType = null)
        }
    }

    private var task: NSTask? = null
    private var serverJob: Job? = null

    /** Invoked (on an arbitrary thread) when the agent process exits. */
    var onTerminated: (() -> Unit)? = null

    val isRunning: Boolean
        get() = task?.isRunning() == true

    /** The SSH agent socket exposed to ssh clients (the App-Group default). */
    val sshAuthSockPath: String
        get() = defaultSshAuthSockPath

    /** The internal IPC socket the binary connects back to. */
    private val ipcSocketPath: String
        get() = NSTemporaryDirectory().trimEnd('/') + "/keyguard-ipc-${getpid()}.sock"

    fun start(scope: CoroutineScope): Boolean {
        if (isRunning) {
            log("SSH agent already running")
            return true
        }
        val binaryPath = findBinary()
        if (binaryPath == null) {
            log("keyguard-ssh-agent binary not found in app bundle")
            return false
        }
        val ipcSocket = ipcSocketPath
        val sshSocket = sshAuthSockPath

        serverJob = scope.launch(Dispatchers.Default) {
            ipcServer.start(ipcSocket)
        }
        return spawn(binaryPath, ipcSocket, sshSocket)
    }

    fun stop() {
        // An intentional stop must not be reported as a crash.
        onTerminated = null
        task?.setTerminationHandler(null)
        task?.let { t ->
            try {
                t.terminate()
            } catch (_: Throwable) {
            }
        }
        task = null
        serverJob?.cancel()
        serverJob = null
        ipcServer.stop()
    }

    private fun spawn(binaryPath: String, ipcSocket: String, sshSocket: String): Boolean {
        val process = NSTask()
        process.setExecutableURL(NSURL.fileURLWithPath(binaryPath))
        process.setArguments(
            listOf(
                "--ipc-socket", ipcSocket,
                "--parent-pid", getpid().toString(),
                "--ssh-socket", sshSocket,
            ),
        )
        val stdinPipe = NSPipe()
        process.setStandardInput(stdinPipe)
        process.setTerminationHandler { exited ->
            log("SSH agent process exited with code ${exited?.terminationStatus}")
            onTerminated?.invoke()
        }

        val launched = runCatching { process.launchAndReturnError(null) }.getOrElse { false }
        if (launched != true) {
            log("Failed to launch keyguard-ssh-agent")
            return false
        }
        task = process

        // Pass the auth token via stdin (hex + newline); keep stdin open so the
        // binary uses EOF as the parent-death signal.
        val tokenLine = (authToken.toHex() + "\n").encodeToByteArray()
        tokenLine.usePinnedData { data ->
            stdinPipe.fileHandleForWriting.writeData(data, null)
        }
        log("SSH agent process started, ssh-auth-sock=$sshSocket")
        return true
    }

    private fun findBinary(): String? = findBundledBinary()
}

@OptIn(ExperimentalForeignApi::class)
private inline fun ByteArray.usePinnedData(block: (NSData) -> Unit) {
    if (isEmpty()) return
    usePinned { pinned ->
        val data = NSData.create(
            bytes = pinned.addressOf(0),
            length = size.toULong(),
        )
        block(data)
    }
}
