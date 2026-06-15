package com.artemchep.keyguard.common.service.sshagent

import java.io.EOFException
import java.net.Socket
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal suspend fun runSshAgentProxyBridge(
    requestProcessor: SshAgentRequestProcessor,
    proxyPort: Int,
    sessionId: ByteArray,
    sessionSecret: ByteArray,
    senderAppInfo: SshAgentMessages.CallerIdentity? = null,
    connectHostCandidates: List<String> = DEFAULT_CONNECT_HOST_CANDIDATES,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    connectDeadlineMs: Long = DEFAULT_CONNECT_DEADLINE_MS,
    connectRetryDelayMs: Long = DEFAULT_CONNECT_RETRY_DELAY_MS,
    socketFactory: () -> Socket = ::Socket,
    monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    delayMs: suspend (Long) -> Unit = { delay(it) },
) {
    val rpcHandler = SshAgentRpcHandler(
        requestProcessor = requestProcessor,
        authenticate = { false },
    )

    withAndroidSshAgentProxySocket(
        proxyPort = proxyPort,
        connectHostCandidates = connectHostCandidates,
        connectTimeoutMs = connectTimeoutMs,
        connectDeadlineMs = connectDeadlineMs,
        connectRetryDelayMs = connectRetryDelayMs,
        socketFactory = socketFactory,
        monotonicTimeMs = monotonicTimeMs,
        delayMs = delayMs,
    ) { socket ->
        val channel = SshAgentTcpProtocol.openAsApp(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            sessionId = sessionId,
            sessionSecret = sessionSecret,
        )
        // Closing the local proxy socket during bridge cancellation or peer disconnect
        // should terminate the session quietly instead of surfacing as an uncaught failure.
        try {
            runSshAgentPacketSession(
                channel = channel,
                rpcHandler = rpcHandler,
                initialContext = SshAgentRpcRequestContext(
                    authenticated = true,
                    allowAuthenticate = false,
                    callerAugmentation = senderAppInfo,
                ),
            )
        } catch (_: EOFException) {
            return@withAndroidSshAgentProxySocket
        } catch (e: SocketException) {
            val skip = e.isExpectedProxyShutdown(socket, currentCoroutineContext().isActive)
            if (skip) {
                return@withAndroidSshAgentProxySocket
            }
            throw e
        }
    }
}

internal fun CoroutineScope.launchSshAgentProxyBridge(
    requestProcessor: SshAgentRequestProcessor,
    proxyPort: Int,
    sessionId: ByteArray,
    sessionSecret: ByteArray,
    senderAppInfo: SshAgentMessages.CallerIdentity? = null,
    connectHostCandidates: List<String> = DEFAULT_CONNECT_HOST_CANDIDATES,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    connectDeadlineMs: Long = DEFAULT_CONNECT_DEADLINE_MS,
    connectRetryDelayMs: Long = DEFAULT_CONNECT_RETRY_DELAY_MS,
    socketFactory: () -> Socket = ::Socket,
    monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    delayMs: suspend (Long) -> Unit = { delay(it) },
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
): Job = launch(
    context = context,
    start = start,
) {
    runSshAgentProxyBridge(
        requestProcessor = requestProcessor,
        proxyPort = proxyPort,
        sessionId = sessionId,
        sessionSecret = sessionSecret,
        senderAppInfo = senderAppInfo,
        connectHostCandidates = connectHostCandidates,
        connectTimeoutMs = connectTimeoutMs,
        connectDeadlineMs = connectDeadlineMs,
        connectRetryDelayMs = connectRetryDelayMs,
        socketFactory = socketFactory,
        monotonicTimeMs = monotonicTimeMs,
        delayMs = delayMs,
    )
}

private fun SocketException.isExpectedProxyShutdown(
    socket: Socket,
    coroutineActive: Boolean,
): Boolean {
    if (!coroutineActive || socket.isClosed || socket.isInputShutdown || socket.isOutputShutdown) {
        return true
    }

    val normalizedMessage = message
        ?.lowercase()
        ?: return false
    return normalizedMessage.contains("socket closed") ||
        normalizedMessage.contains("broken pipe") ||
        normalizedMessage.contains("connection reset") ||
        normalizedMessage.contains("connection abort")
}

internal fun buildAndroidSshAgentCallerIdentity(
    pid: Int? = null,
    uid: Int? = null,
    gid: Int? = null,
    processName: String? = null,
    executablePath: String? = null,
    appName: String? = null,
    appBundlePath: String? = null,
): SshAgentMessages.CallerIdentity? {
    val normalizedProcessName = processName?.takeIf { it.isNotBlank() }
    val normalizedExecutablePath = executablePath?.takeIf { it.isNotBlank() }
    val normalizedAppName = appName?.takeIf { it.isNotBlank() }
    val normalizedAppBundlePath = appBundlePath?.takeIf { it.isNotBlank() }
    val hasAnyData = pid != null ||
            uid != null ||
            gid != null ||
            normalizedProcessName != null ||
            normalizedExecutablePath != null ||
            normalizedAppName != null ||
            normalizedAppBundlePath != null

    if (!hasAnyData) {
        return null
    }

    return SshAgentMessages.CallerIdentity(
        pid = pid ?: 0,
        uid = uid ?: 0,
        gid = gid ?: 0,
        processName = normalizedProcessName.orEmpty(),
        executablePath = normalizedExecutablePath.orEmpty(),
        appName = normalizedAppName.orEmpty(),
        appBundlePath = normalizedAppBundlePath.orEmpty(),
    )
}

internal fun mergeAndroidSshAgentCallerIdentity(
    caller: SshAgentMessages.CallerIdentity?,
    senderAppInfo: SshAgentMessages.CallerIdentity?,
): SshAgentMessages.CallerIdentity? {
    if (senderAppInfo == null) {
        return caller
    }

    val appName = senderAppInfo.appName
        .takeIf { it.isNotBlank() }
    val appBundlePath = senderAppInfo.appBundlePath
        .takeIf { it.isNotBlank() }
    if (caller == null) {
        return buildAndroidSshAgentCallerIdentity(
            appName = appName,
            appBundlePath = appBundlePath,
        )
    }

    return caller.copy(
        appName = appName ?: caller.appName,
        appBundlePath = appBundlePath ?: caller.appBundlePath,
    )
}
