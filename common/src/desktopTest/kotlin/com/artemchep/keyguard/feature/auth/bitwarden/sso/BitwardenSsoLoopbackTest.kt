package com.artemchep.keyguard.feature.auth.bitwarden.sso

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.http.Url
import io.ktor.http.parseQueryString
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class BitwardenSsoLoopbackTest {
    @BeforeTest
    fun setup() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @AfterTest
    fun teardown() { Dispatchers.resetMain() }

    private fun prompt() = SsoBrowserPrompt(
        SsoRequest(ServerEnv(baseUrl = "https://vault.test"), "user@example.test", "state", "verifier", "challenge"),
    )

    /** Starts the receiver; returns it and the redirect URL it hands to the browser. */
    private suspend fun CoroutineScope.startReceiver(
        prompt: SsoBrowserPrompt,
        successText: String = "Complete",
    ): Pair<Deferred<Unit>, Url> {
        val redirect = CompletableDeferred<Url>()
        val receiver = async {
            receiveSsoLoopback(prompt, successText) {
                redirect.complete(Url(parseQueryString(Url(it).fragment.substringAfter('?'))["redirectUri"]!!))
            }
        }
        return receiver to redirect.await()
    }

    @Test
    fun ignoresForgedCallbackThenAcceptsValidCallback() = runBlocking {
        withTimeout(10_000) {
            val prompt = prompt()
            val (receiver, url) = startReceiver(prompt)
            assertTrue(send(url, "wrong").startsWith("HTTP/1.1 400"))
            assertFalse(prompt.result.isCompleted)
            assertTrue(send(url, "state_identifier=org").startsWith("HTTP/1.1 200"))
            assertEquals("code", prompt.result.await().code)
            receiver.await()
            assertPortReleased(url.port)
        }
    }

    @Test
    fun matchingDenialFinishesWithoutWaitingForTimeout() = runBlocking {
        withTimeout(10_000) {
            val prompt = prompt()
            val (receiver, url) = startReceiver(prompt, "Return to the app")
            assertTrue(send(url, "state", denied = true).startsWith("HTTP/1.1 200"))
            val failure = assertFailsWith<SsoException> { prompt.result.await() }
            assertEquals(SsoFailure.AUTHENTICATION, failure.failure)
            receiver.await()
        }
    }

    @Test
    fun acceptsCallbackOnIpv6Loopback() = runBlocking {
        if (ipv6Loopback() == null) return@runBlocking
        withTimeout(10_000) {
            val prompt = prompt()
            val (receiver, url) = startReceiver(prompt)
            assertTrue(send(url, "state", address = "::1").startsWith("HTTP/1.1 200"))
            assertEquals("code", prompt.result.await().code)
            receiver.await()
            assertPortReleased(url.port)
        }
    }

    @Test
    fun skipsPortTakenOnIpv6Loopback() = runBlocking {
        val ipv6 = ipv6Loopback() ?: return@runBlocking
        // Another app holds the first registered port on ::1 only.
        val blocker = runCatching { ServerSocket(8065, 1, ipv6) }.getOrNull() ?: return@runBlocking
        blocker.use {
            withTimeout(10_000) {
                val prompt = prompt()
                val (receiver, url) = startReceiver(prompt)
                assertNotEquals(blocker.localPort, url.port)
                assertTrue(send(url, "state", address = "::1").startsWith("HTTP/1.1 200"))
                assertEquals("code", prompt.result.await().code)
                receiver.await()
            }
        }
    }

    @Test
    fun cancellationClosesListener() = runBlocking {
        withTimeout(10_000) {
            val (receiver, url) = startReceiver(prompt())
            receiver.cancelAndJoin()
            assertPortReleased(url.port)
        }
    }

    @Test
    fun slowClientCannotHoldListener() = runBlocking {
        withTimeout(10_000) {
            val prompt = prompt()
            val (receiver, url) = startReceiver(prompt)
            // Each byte arrives well within the read timeout, but the header never ends.
            val slow = launch(Dispatchers.IO) {
                try {
                    Socket("127.0.0.1", url.port).use { socket ->
                        repeat(30) {
                            socket.getOutputStream().apply { write('G'.code); flush() }
                            delay(300)
                        }
                    }
                } catch (_: IOException) {
                    // The receiver dropped the connection.
                }
            }
            delay(500)
            // The valid callback waits behind the slow one, within the client's read timeout.
            assertTrue(send(url, "state").startsWith("HTTP/1.1 200"))
            assertEquals("code", prompt.result.await().code)
            receiver.await()
            slow.cancelAndJoin()
        }
    }

    private suspend fun send(
        url: Url,
        state: String,
        denied: Boolean = false,
        address: String = "127.0.0.1",
    ): String = withContext(Dispatchers.IO) {
        Socket(address, url.port).use { socket ->
            socket.soTimeout = 3000
            val result = if (denied) "error=access_denied" else "code=code"
            socket.getOutputStream().write(
                "GET /?$result&state=$state HTTP/1.1\r\nHost: localhost:${url.port}\r\n\r\n".toByteArray(),
            )
            socket.getInputStream().bufferedReader().readText()
        }
    }
}

private fun assertPortReleased(port: Int) {
    listOfNotNull(InetAddress.getByName("127.0.0.1"), ipv6Loopback()).forEach { address ->
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, port))
        }
    }
}

/** Null when the machine has no IPv6 loopback; the receiver then listens on 127.0.0.1 only. */
private fun ipv6Loopback(): InetAddress? = InetAddress.getByName("::1").takeIf { address ->
    runCatching { ServerSocket(0, 1, address).close() }.isSuccess
}
