package com.artemchep.keyguard.common.service.sshagent

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val DEFAULT_CONNECT_TIMEOUT_MS = 1_000
internal const val DEFAULT_CONNECT_DEADLINE_MS = 10_000L
internal const val DEFAULT_CONNECT_RETRY_DELAY_MS = 200L

internal val DEFAULT_CONNECT_HOST_CANDIDATES = listOf(
    "127.0.0.1",
    "::1",
)

private class ProxyConnectionException(
    message: String,
    cause: Throwable?,
) : Exception(message, cause)

private suspend fun defaultDelayMs(
    millis: Long,
) {
    delay(millis)
}

internal suspend fun <T> withAndroidSshAgentProxySocket(
    proxyPort: Int,
    connectHostCandidates: List<String> = DEFAULT_CONNECT_HOST_CANDIDATES,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    connectDeadlineMs: Long = DEFAULT_CONNECT_DEADLINE_MS,
    connectRetryDelayMs: Long = DEFAULT_CONNECT_RETRY_DELAY_MS,
    socketFactory: () -> Socket = ::Socket,
    monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    delayMs: suspend (Long) -> Unit = ::defaultDelayMs,
    block: suspend (Socket) -> T,
): T = coroutineScope {
    val deadlineAt = monotonicTimeMs() + connectDeadlineMs
    val attemptOutcomes = linkedMapOf<String, String>()
    var lastFailure: Exception? = null

    while (monotonicTimeMs() < deadlineAt) {
        coroutineContext.ensureActive()

        for (host in connectHostCandidates) {
            val remainingMs = deadlineAt - monotonicTimeMs()
            if (remainingMs <= 0L) {
                break
            }

            val attemptTimeoutMs = minOf(connectTimeoutMs.toLong(), remainingMs).toInt()
            val socket = socketFactory()

            // Listen for the cancellation of the scope from
            // the outside. This is needed because the socket.connect()
            // might be blocking as for a while.
            val closeOnCancellationJob = launch(
                start = CoroutineStart.UNDISPATCHED,
            ) {
                try {
                    awaitCancellation()
                } finally {
                    socket.close()
                }
            }

            // 1. Try to connect to the socket
            try {
                socket.connect(
                    InetSocketAddress(host, proxyPort),
                    attemptTimeoutMs,
                )
            } catch (e: Exception) {
                val cancelled =
                    e is CancellationException || socket.isClosed || !coroutineContext.isActive
                closeOnCancellationJob.cancel()
                socket.close()
                if (cancelled) {
                    val msg = "Proxy connection attempt was cancelled for port=$proxyPort"
                    throw CancellationException(msg).apply {
                        initCause(e)
                    }
                }
                lastFailure = e
                attemptOutcomes[host] = e.message ?: e::class.java.simpleName
                continue
            }

            // 2. Keep the socket open
            return@coroutineScope try {
                block(socket)
            } finally {
                closeOnCancellationJob.cancel()
                socket.close()
            }
        }

        if (monotonicTimeMs() < deadlineAt) {
            coroutineContext.ensureActive()
            delayMs(connectRetryDelayMs)
        }
    }

    val attemptedHosts = attemptOutcomes.entries
        .joinToString { (host, outcome) ->
            "$host($outcome)"
        }
    val failureMode = if (lastFailure == null) {
        "deadline_expired_without_connect_attempt"
    } else {
        "deadline_expired_after_retries"
    }
    throw ProxyConnectionException(
        message = buildString {
            append("Failed to connect to localhost proxy port=")
            append(proxyPort)
            append(" within ")
            append(connectDeadlineMs)
            append("ms; mode=")
            append(failureMode)
            append("; attempted=")
            append(attemptedHosts.ifEmpty { "none" })
        },
        cause = lastFailure,
    )
}
