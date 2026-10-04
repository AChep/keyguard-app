package com.artemchep.keyguard.integration.s3

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Raw HTTP lets tests send incomplete framing that a normal server would repair. */
internal class S3HttpFixture(
    private val handle: (S3HttpRequest, Socket) -> Unit,
) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "s3-http-fixture").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val failures = ConcurrentLinkedQueue<Throwable>()
    val requests = CopyOnWriteArrayList<S3HttpRequest>()
    val endpoint = "http://127.0.0.1:${listener.localPort}"

    init {
        workers.submit {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    sockets += socket
                    workers.submit { serve(socket) }
                }
            } catch (e: SocketException) {
                if (!listener.isClosed) failures += e
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun serve(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 10_000
                val request = readRequest(socket)
                requests += request
                handle(request, socket)
            }
        } catch (e: Throwable) {
            if (!listener.isClosed) failures += e
        } finally {
            sockets -= socket
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { it.close() }
        workers.shutdown()
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "HTTP fixture workers did not stop")
        failures.firstOrNull()?.let { throw AssertionError("HTTP fixture failed", it) }
    }

    private fun readRequest(socket: Socket): S3HttpRequest {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = input.readHttpLine().split(' ')
        assertEquals(3, requestLine.size)
        val headers = buildMap {
            while (true) {
                val line = input.readHttpLine()
                if (line.isEmpty()) break
                put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
            }
        }
        val size = headers["content-length"]?.toInt() ?: 0
        require(size in 0..1024 * 1024) { "Fixture only accepts small request bodies" }
        val body = input.readNBytes(size)
        assertEquals(size, body.size, "Request ended before its declared Content-Length")
        return S3HttpRequest(requestLine[0], requestLine[1], headers, body)
    }
}

internal data class S3HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

private fun BufferedInputStream.readHttpLine(): String = buildString {
    while (true) {
        val byte = read()
        check(byte >= 0) { "EOF inside HTTP headers" }
        if (byte == '\n'.code) break
        if (byte != '\r'.code) append(byte.toChar())
        check(length < 16 * 1024) { "HTTP header too large for fixture" }
    }
}

internal fun Socket.respond(
    status: Int = 200,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
) {
    writeHeaders(status, mapOf("Content-Length" to body.encodeToByteArray().size.toString()) + headers)
    getOutputStream().write(body.encodeToByteArray())
    getOutputStream().flush()
}

internal fun Socket.writeHeaders(status: Int = 200, headers: Map<String, String> = emptyMap()) {
    val text = buildString {
        append("HTTP/1.1 $status Test\r\nConnection: close\r\n")
        headers.forEach { (name, value) -> append("$name: $value\r\n") }
        append("\r\n")
    }
    getOutputStream().write(text.encodeToByteArray())
    getOutputStream().flush()
}

internal fun Socket.awaitDisconnect() {
    try {
        assertEquals(-1, getInputStream().read(), "Expected the client to release the connection")
    } catch (_: SocketException) {
        // A reset is also a valid way to cancel the response.
    }
}

internal fun Socket.writeUntilDisconnected() {
    val chunk = ByteArray(8192) { 'x'.code.toByte() }
    try {
        repeat(5000) { getOutputStream().write(chunk) }
        getOutputStream().flush()
        awaitDisconnect()
    } catch (_: IOException) {
        // The client must stop consuming this unfinished, oversized response.
    }
}
