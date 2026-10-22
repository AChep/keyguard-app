package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.AgentPacketSession
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.isUnsafeAgentCallerDisplayCodePoint
import com.artemchep.keyguard.common.service.agent.serve
import java.io.EOFException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal suspend fun runSshAgentProxyBridge(
    requestProcessor: SshAgentRequestProcessor,
    proxyPort: Int,
    sessionId: ByteArray,
    sessionSecret: ByteArray,
    senderAppInfo: CallerIdentity? = null,
    onOversizeResponse: (size: Int) -> Unit = {},
    connectHostCandidates: List<String> = DEFAULT_CONNECT_HOST_CANDIDATES,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    connectDeadlineMs: Long = DEFAULT_CONNECT_DEADLINE_MS,
    connectRetryDelayMs: Long = DEFAULT_CONNECT_RETRY_DELAY_MS,
    handshakeTimeoutMs: Long = DEFAULT_PROXY_HANDSHAKE_TIMEOUT_MS,
    packetReadTimeoutMs: Long = DEFAULT_SSH_PROXY_PACKET_READ_TIMEOUT_MS,
    packetWriteTimeoutMs: Long = DEFAULT_PROXY_PACKET_WRITE_TIMEOUT_MS,
    socketFactory: () -> Socket = ::Socket,
    monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    // Null default (not `= { delay(it) }`) to avoid a Kotlin 2.4.0 JVM-backend crash;
    // see withAndroidSshAgentProxySocket. Forwarded as-is to the connector.
    delayMs: (suspend (Long) -> Unit)? = null,
) {
    val rpcHandler = SshAgentRpcHandler(
        requestProcessor = CallerMergingSshAgentRequestProcessor(
            delegate = requestProcessor,
            senderAppInfo = senderAppInfo,
            replaceCallerAuthorization = true,
        ),
    )

    runAndroidAgentProxySession(
        agentName = "SSH agent",
        proxyPort = proxyPort,
        sessionId = sessionId,
        sessionSecret = sessionSecret,
        connectHostCandidates = connectHostCandidates,
        connectTimeoutMs = connectTimeoutMs,
        connectDeadlineMs = connectDeadlineMs,
        connectRetryDelayMs = connectRetryDelayMs,
        handshakeTimeoutMs = handshakeTimeoutMs,
        packetReadTimeoutMs = packetReadTimeoutMs,
        packetWriteTimeoutMs = packetWriteTimeoutMs,
        socketFactory = socketFactory,
        monotonicTimeMs = monotonicTimeMs,
        delayMs = delayMs,
    ) { maxPacketSize ->
        SshAgentPacketSession(
            rpcHandler = rpcHandler,
            authenticatedByTransport = true,
            maxPacketSize = maxPacketSize,
            onOversizeResponse = onOversizeResponse,
        )
    }
}

/**
 * Runs every request's caller through [mergeAndroidSshAgentCallerIdentity],
 * which bounds and strips its display fields. The Android bridge attributes
 * requests to the app that sent the broadcast and never trusts the helper's
 * own authorization; desktop passes no sender and keeps the authorization.
 */
internal class CallerMergingSshAgentRequestProcessor(
    private val delegate: SshAgentRequestProcessor,
    private val senderAppInfo: CallerIdentity?,
    private val replaceCallerAuthorization: Boolean,
) : SshAgentRequestProcessor {
    override suspend fun listKeys(
        caller: CallerIdentity?,
    ): SshAgentRequestProcessor.ListKeysResult = delegate.listKeys(caller.merged())

    override suspend fun signData(
        request: SshAgentMessages.SignDataRequest,
    ): SshAgentRequestProcessor.SignDataResult =
        delegate.signData(request.copy(caller = request.caller.merged()))

    private fun CallerIdentity?.merged(): CallerIdentity? = mergeAndroidSshAgentCallerIdentity(
        caller = this,
        senderAppInfo = senderAppInfo,
        replaceCallerAuthorization = replaceCallerAuthorization,
    )
}

/**
 * Connects back to a Termux helper's proxy port, completes the session
 * handshake and serves a [session] with packet I/O bounded by socket deadlines.
 * The read deadline doubles as the idle timeout between requests, so each
 * protocol picks one that outlasts its helper's own idle limit.
 */
internal suspend fun runAndroidAgentProxySession(
    agentName: String,
    proxyPort: Int,
    sessionId: ByteArray,
    sessionSecret: ByteArray,
    packetReadTimeoutMs: Long,
    connectHostCandidates: List<String> = DEFAULT_CONNECT_HOST_CANDIDATES,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    connectDeadlineMs: Long = DEFAULT_CONNECT_DEADLINE_MS,
    connectRetryDelayMs: Long = DEFAULT_CONNECT_RETRY_DELAY_MS,
    handshakeTimeoutMs: Long = DEFAULT_PROXY_HANDSHAKE_TIMEOUT_MS,
    packetWriteTimeoutMs: Long = DEFAULT_PROXY_PACKET_WRITE_TIMEOUT_MS,
    socketFactory: () -> Socket = ::Socket,
    monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    // Null default (not `= { delay(it) }`) to avoid a Kotlin 2.4.0 JVM-backend crash;
    // see withAndroidSshAgentProxySocket. Forwarded as-is to the connector.
    delayMs: (suspend (Long) -> Unit)? = null,
    /** Creates the session of a connection, whose packets the transport caps at the given size. */
    session: (maxPacketSize: Int) -> AgentPacketSession<*, *>,
) {
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
        val channel = runSocketOperationWithDeadline(
            socket = socket,
            timeoutMs = handshakeTimeoutMs,
            operationName = "$agentName proxy handshake",
        ) {
            SshAgentTcpProtocol.openAsApp(
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                sessionId = sessionId,
                sessionSecret = sessionSecret,
            )
        }
        runProxySessionQuietly(socket) {
            session(SshAgentTcpProtocol.MAX_FRAME_PAYLOAD_SIZE).serve(
                readPacket = {
                    runSocketOperationWithDeadline(
                        socket = socket,
                        timeoutMs = packetReadTimeoutMs,
                        operationName = "$agentName proxy packet read",
                    ) {
                        channel.readPacket()
                    }
                },
                writePacket = { packet ->
                    runSocketOperationWithDeadline(
                        socket = socket,
                        timeoutMs = packetWriteTimeoutMs,
                        operationName = "$agentName proxy packet write",
                    ) {
                        channel.writePacket(packet)
                    }
                    true
                },
            )
        }
    }
}

/**
 * Runs a proxy session. Closing the local proxy socket during bridge cancellation
 * or a peer disconnect ends the session quietly instead of surfacing as an
 * uncaught failure.
 */
internal suspend fun runProxySessionQuietly(
    socket: Socket,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (_: EOFException) {
        return
    } catch (_: SocketTimeoutException) {
        return
    } catch (e: SocketException) {
        if (!e.isExpectedProxyShutdown(socket, currentCoroutineContext().isActive)) {
            throw e
        }
    }
}

internal fun CoroutineScope.launchSshAgentProxyBridge(
    requestProcessor: SshAgentRequestProcessor,
    proxyPort: Int,
    sessionId: ByteArray,
    sessionSecret: ByteArray,
    senderAppInfo: CallerIdentity? = null,
    connectHostCandidates: List<String> = DEFAULT_CONNECT_HOST_CANDIDATES,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    connectDeadlineMs: Long = DEFAULT_CONNECT_DEADLINE_MS,
    connectRetryDelayMs: Long = DEFAULT_CONNECT_RETRY_DELAY_MS,
    handshakeTimeoutMs: Long = DEFAULT_PROXY_HANDSHAKE_TIMEOUT_MS,
    packetReadTimeoutMs: Long = DEFAULT_SSH_PROXY_PACKET_READ_TIMEOUT_MS,
    packetWriteTimeoutMs: Long = DEFAULT_PROXY_PACKET_WRITE_TIMEOUT_MS,
    socketFactory: () -> Socket = ::Socket,
    monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    // Null default (not `= { delay(it) }`) to avoid a Kotlin 2.4.0 JVM-backend crash;
    // see withAndroidSshAgentProxySocket. Forwarded as-is.
    delayMs: (suspend (Long) -> Unit)? = null,
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
        handshakeTimeoutMs = handshakeTimeoutMs,
        packetReadTimeoutMs = packetReadTimeoutMs,
        packetWriteTimeoutMs = packetWriteTimeoutMs,
        socketFactory = socketFactory,
        monotonicTimeMs = monotonicTimeMs,
        delayMs = delayMs,
    )
}

internal const val DEFAULT_PROXY_HANDSHAKE_TIMEOUT_MS = 10_000L
// Matches CLIENT_PACKET_TIMEOUT of the Termux SSH agent helper.
internal const val DEFAULT_SSH_PROXY_PACKET_READ_TIMEOUT_MS = 120_000L
internal const val DEFAULT_PROXY_PACKET_WRITE_TIMEOUT_MS = 10_000L

/**
 * Runs one blocking socket operation with an absolute deadline. Java's
 * SO_TIMEOUT is reset by each partial read and has no portable write-timeout
 * equivalent, so a sibling timer closes the socket to interrupt both slow
 * header/body reads and blocked localhost writes.
 */
internal suspend fun <T> runSocketOperationWithDeadline(
    socket: Socket,
    timeoutMs: Long,
    operationName: String,
    block: () -> T,
): T = coroutineScope {
    require(timeoutMs > 0L) { "Socket operation timeout must be positive" }
    val expired = AtomicBoolean(false)
    val deadlineJob = launch(start = CoroutineStart.UNDISPATCHED) {
        delay(timeoutMs)
        expired.set(true)
        socket.close()
    }

    try {
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            block()
        }
        if (expired.get()) {
            throw SocketTimeoutException("$operationName timed out after ${timeoutMs}ms")
        }
        result
    } catch (e: Exception) {
        if (expired.get() && e !is SocketTimeoutException) {
            throw SocketTimeoutException("$operationName timed out after ${timeoutMs}ms").apply {
                initCause(e)
            }
        }
        throw e
    } finally {
        deadlineJob.cancelAndJoin()
    }
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
    authorization: CallerAuthorization? = null,
): CallerIdentity? {
    val normalizedProcessName = sanitizeAndroidCallerDisplayValue(
        processName,
        MAX_AGENT_CALLER_NAME_LENGTH,
    )
    val normalizedExecutablePath = sanitizeAndroidCallerDisplayValue(
        executablePath,
        MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH,
    )
    val normalizedAppName = sanitizeAndroidCallerDisplayValue(
        appName,
        MAX_AGENT_CALLER_NAME_LENGTH,
    )
    val normalizedAppBundlePath = sanitizeAndroidCallerDisplayValue(
        appBundlePath,
        MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH,
    )
    val hasAnyData = pid != null ||
            uid != null ||
            gid != null ||
            normalizedProcessName != null ||
            normalizedExecutablePath != null ||
            normalizedAppName != null ||
            normalizedAppBundlePath != null ||
            authorization != null

    if (!hasAnyData) {
        return null
    }

    return CallerIdentity(
        pid = pid ?: 0,
        uid = uid ?: 0,
        gid = gid ?: 0,
        processName = normalizedProcessName.orEmpty(),
        executablePath = normalizedExecutablePath.orEmpty(),
        appName = normalizedAppName.orEmpty(),
        appBundlePath = normalizedAppBundlePath.orEmpty(),
        authorization = authorization,
    )
}

/**
 * Derives a stable principal from framework-verified Android package metadata.
 * Callers must obtain both the package name and signing certificates from the
 * Android framework rather than from the SSH request payload.
 */
internal fun buildAndroidFrameworkPackageAuthorization(
    packageName: String?,
    signingCertificates: List<ByteArray>,
): CallerAuthorization? {
    val normalizedPackageName = packageName
        ?.takeIf { it.isNotBlank() }
        ?: return null
    if (signingCertificates.isEmpty() || signingCertificates.any(ByteArray::isEmpty)) {
        return null
    }

    val digest = MessageDigest.getInstance("SHA-256")
    val certificateDigests = signingCertificates
        .map { certificate -> digest.digest(certificate) }
        .sortedWith { left, right -> compareUnsigned(left, right) }
    digest.reset()
    digest.update("keyguard.android.framework-package.v1".encodeToByteArray())
    digest.update(0)
    val packageBytes = normalizedPackageName.encodeToByteArray()
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(packageBytes.size).array())
    digest.update(packageBytes)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(certificateDigests.size).array())
    certificateDigests.forEach(digest::update)

    return androidBridgeAuthorization(digest.digest())
}

/**
 * Creates authorization for one admitted Android bridge. The connection
 * remains cacheable when framework package evidence is unavailable, while a
 * valid package principal adds the wider stable-application subject.
 */
internal fun androidBridgeAuthorization(
    principalFingerprint: ByteArray?,
    connectionFingerprint: ByteArray = ByteArray(
        AgentCallerAuthorizationSchema.FINGERPRINT_SIZE,
    ).also(SecureRandom()::nextBytes),
): CallerAuthorization? {
    if (connectionFingerprint.size != AgentCallerAuthorizationSchema.FINGERPRINT_SIZE) {
        return null
    }

    val subjects = principalFingerprint
        ?.takeIf { it.size == AgentCallerAuthorizationSchema.FINGERPRINT_SIZE }
        ?.let { fingerprint ->
            listOf(
                CallerAuthorizationSubject(
                    kind = AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION,
                    evidenceSource =
                        AgentCallerAuthorizationSchema.EvidenceSource.ANDROID_FRAMEWORK_PACKAGE,
                    fingerprint = fingerprint.copyOf(),
                ),
            )
        }
        .orEmpty()
    return CallerAuthorization(
        connectionFingerprint = connectionFingerprint.copyOf(),
        subjects = subjects,
    )
}

private fun compareUnsigned(
    left: ByteArray,
    right: ByteArray,
): Int {
    left.indices.forEach { index ->
        val comparison = left[index].toUByte().compareTo(right[index].toUByte())
        if (comparison != 0) {
            return comparison
        }
    }
    return left.size.compareTo(right.size)
}

internal fun mergeAndroidSshAgentCallerIdentity(
    caller: CallerIdentity?,
    senderAppInfo: CallerIdentity?,
    replaceCallerAuthorization: Boolean = false,
): CallerIdentity? {
    if (senderAppInfo == null) {
        return if (replaceCallerAuthorization) {
            // The Android bridge could not attribute the outer broadcast to
            // a framework-verified package. Discard all request-supplied
            // display identity along with its authorization so a proxy cannot
            // impersonate a familiar app in the approval dialog.
            null
        } else {
            caller?.sanitizeAndroidCallerIdentity()
        }
    }

    val sanitizedSenderAppInfo = senderAppInfo.sanitizeAndroidCallerIdentity()
    val hasVerifiedAndroidPackage = sanitizedSenderAppInfo.authorization
        .hasVerifiedAndroidFrameworkPackageSubject()
    if (replaceCallerAuthorization && !hasVerifiedAndroidPackage) {
        // A connection-only bridge authorization is safe for approval reuse
        // within this bridge, but does not authenticate any request-supplied
        // display metadata. Keep the approval dialog anonymous rather than
        // allowing the proxy to impersonate a familiar process or app.
        return buildAndroidSshAgentCallerIdentity(
            authorization = sanitizedSenderAppInfo.authorization,
        )
    }

    val appName = sanitizedSenderAppInfo.appName
        .takeIf(String::isNotEmpty)
    val appBundlePath = sanitizedSenderAppInfo.appBundlePath
        .takeIf(String::isNotEmpty)
    val sanitizedCaller = caller?.sanitizeAndroidCallerIdentity()
    if (sanitizedCaller == null) {
        return buildAndroidSshAgentCallerIdentity(
            appName = appName,
            appBundlePath = appBundlePath,
            authorization = sanitizedSenderAppInfo.authorization,
        )
    }

    return sanitizedCaller.copy(
        appName = if (replaceCallerAuthorization) {
            appName.orEmpty()
        } else {
            appName ?: sanitizedCaller.appName
        },
        appBundlePath = if (replaceCallerAuthorization) {
            appBundlePath.orEmpty()
        } else {
            appBundlePath ?: sanitizedCaller.appBundlePath
        },
        authorization = if (replaceCallerAuthorization) {
            sanitizedSenderAppInfo.authorization
        } else {
            sanitizedCaller.authorization ?: sanitizedSenderAppInfo.authorization
        },
    )
}

private fun CallerAuthorization?.hasVerifiedAndroidFrameworkPackageSubject(): Boolean {
    val authorization = this ?: return false
    if (
        authorization.connectionFingerprint.size !=
        AgentCallerAuthorizationSchema.FINGERPRINT_SIZE
    ) {
        return false
    }
    val subject = authorization.subjects.singleOrNull()
        ?: return false
    return subject.kind == AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION &&
        subject.evidenceSource ==
        AgentCallerAuthorizationSchema.EvidenceSource.ANDROID_FRAMEWORK_PACKAGE &&
        subject.fingerprint.size == AgentCallerAuthorizationSchema.FINGERPRINT_SIZE
}

private fun CallerIdentity.sanitizeAndroidCallerIdentity(
): CallerIdentity = copy(
    processName = sanitizeAndroidCallerDisplayValue(
        processName,
        MAX_AGENT_CALLER_NAME_LENGTH,
    ).orEmpty(),
    executablePath = sanitizeAndroidCallerDisplayValue(
        executablePath,
        MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH,
    ).orEmpty(),
    appName = sanitizeAndroidCallerDisplayValue(
        appName,
        MAX_AGENT_CALLER_NAME_LENGTH,
    ).orEmpty(),
    appBundlePath = sanitizeAndroidCallerDisplayValue(
        appBundlePath,
        MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH,
    ).orEmpty(),
)

private fun sanitizeAndroidCallerDisplayValue(
    value: String?,
    maxLength: Int,
): String? {
    if (value == null) {
        return null
    }

    val output = StringBuilder(minOf(value.length, maxLength))
    var index = 0
    while (index < value.length && output.length < maxLength) {
        val codePoint = value.codePointAt(index)
        index += Character.charCount(codePoint)
        if (isUnsafeAgentCallerDisplayCodePoint(codePoint)) {
            continue
        }

        if (Character.isWhitespace(codePoint)) {
            if (output.isNotEmpty() && output.last() != ' ') {
                output.append(' ')
            }
            continue
        }

        val codePointLength = Character.charCount(codePoint)
        if (output.length + codePointLength > maxLength) {
            break
        }
        output.appendCodePoint(codePoint)
    }

    return output.toString()
        .trim()
        .takeIf(String::isNotEmpty)
}
