package com.artemchep.keyguard.feature.auth.bitwarden.sso

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FIRST_CALLBACK_PORT = 8065
private const val LAST_CALLBACK_PORT = 8070
private const val ACCEPT_TIMEOUT_MS = 250
private const val READ_TIMEOUT_MS = 2000
private const val MAX_HEADER_SIZE = 8192

/**
 * Small, bounded, loopback-only HTTP receiver for the server's registered desktop redirects.
 * The attempt's owner bounds it with a timeout.
 */
internal suspend fun receiveSsoLoopback(
    prompt: SsoBrowserPrompt,
    successText: String,
    launch: suspend (String) -> Unit,
) = withContext(Dispatchers.IO) {
    val servers = bindSsoListeners()
    try {
        val redirect = "http://localhost:${servers.first().localPort}"
        withContext(Dispatchers.Main) { launch(prompt.request.browserUrl(redirect)) }
        acceptSsoCallbacks(servers, prompt, redirect, successText)
    } finally {
        servers.forEach(ServerSocket::close)
    }
}

private suspend fun acceptSsoCallbacks(
    servers: List<ServerSocket>,
    prompt: SsoBrowserPrompt,
    redirect: String,
    successText: String,
) = coroutineScope {
    servers.forEach { server ->
        launch {
            while (!prompt.result.isCompleted) {
                currentCoroutineContext().ensureActive()
                val connection = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                connection.use { socket ->
                    try {
                        receiveSsoCallback(socket, prompt, redirect, successText)
                    } catch (_: IOException) {
                        // A broken or unrelated local request must not consume the attempt.
                    }
                }
            }
        }
    }
}

/**
 * Browsers resolve localhost to both loopback addresses and use the first one that accepts.
 * Holding the port on each of them stops another app from receiving the callback.
 */
private fun bindSsoListeners(): List<ServerSocket> {
    val addresses = loopbackAddresses()
    return (FIRST_CALLBACK_PORT..LAST_CALLBACK_PORT).firstNotNullOfOrNull { port ->
        val servers = mutableListOf<ServerSocket>()
        try {
            addresses.mapTo(servers) { address -> bindSsoListener(address, port) }
        } catch (_: IOException) {
            servers.forEach(ServerSocket::close)
            null
        }
    } ?: throw SsoException(SsoFailure.PORTS)
}

/** Without an IPv6 loopback, browsers fall back to 127.0.0.1 and no app can listen on ::1. */
private fun loopbackAddresses(): List<InetAddress> {
    val ipv4 = InetAddress.getByName("127.0.0.1")
    val ipv6 = InetAddress.getByName("::1")
    val hasIpv6 = try {
        ServerSocket().use { it.bind(InetSocketAddress(ipv6, 0)) }
        true
    } catch (_: IOException) {
        false
    }
    return listOfNotNull(ipv4, ipv6.takeIf { hasIpv6 })
}

private fun bindSsoListener(address: InetAddress, port: Int): ServerSocket {
    val socket = ServerSocket()
    try {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, port))
        socket.soTimeout = ACCEPT_TIMEOUT_MS
    } catch (e: IOException) {
        socket.close()
        throw e
    }
    return socket
}

/**
 * Reads the request header within one deadline: a client that sends it byte
 * by byte must not hold the listener, or delay its cancellation.
 */
private fun readSsoHeader(socket: Socket): String {
    val deadline = TimeSource.Monotonic.markNow() + READ_TIMEOUT_MS.milliseconds
    val input = socket.getInputStream().buffered()
    val header = StringBuilder()
    while (header.length < MAX_HEADER_SIZE && !header.endsWith("\r\n\r\n")) {
        if (deadline.hasPassedNow()) throw SocketTimeoutException()
        // A zero timeout would wait forever.
        socket.soTimeout = (-deadline.elapsedNow()).inWholeMilliseconds.toInt().coerceAtLeast(1)
        val byte = input.read()
        if (byte < 0) break
        header.append(byte.toChar())
    }
    return header.toString()
}

private fun receiveSsoCallback(socket: Socket, prompt: SsoBrowserPrompt, redirect: String, successText: String) {
    val header = readSsoHeader(socket)
    val firstLine = header.substringBefore("\r\n").split(' ')
    val target = firstLine.getOrNull(1)
    val host = header.lines().firstOrNull { it.startsWith("Host:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()
    val validMethod = firstLine.firstOrNull() == "GET" && target?.startsWith("/?") == true
    val validHost = host == "localhost:${socket.localPort}" && header.endsWith("\r\n\r\n")
    val result = if (validMethod && validHost) {
        runCatching { prompt.request.accept(redirect + target, redirect) }
            // A denial with the correct state is also a terminal callback. Unrelated requests keep listening.
            .takeIf { it.isSuccess || (it.exceptionOrNull() as? SsoException)?.failure == SsoFailure.AUTHENTICATION }
    } else {
        null
    }
    val body = (if (result != null) successText else "").toByteArray(Charsets.UTF_8)
    val status = if (result != null) "200 OK" else "400 Bad Request"
    val response = "HTTP/1.1 $status\r\n" +
        "Content-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n" +
        "Connection: close\r\nContent-Length: ${body.size}\r\n\r\n"
    socket.getOutputStream().apply {
        write(response.toByteArray(Charsets.US_ASCII))
        write(body)
        flush()
    }
    if (result != null) prompt.result.completeWith(result)
}
