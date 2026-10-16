package com.artemchep.keyguard.integration.bitwarden

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

/** One Vaultwarden server plus Keyguard's HTTP client and crypto. */
internal class VaultwardenFixture private constructor(
    val server: VaultwardenContainer,
) : VaultwardenApi(server.baseUrl) {
    /**
     * Runs a test in real time, so waits on the server do not time out early the way
     * they do on `runTest`'s virtual clock, and attaches the server logs to its failure.
     */
    @Suppress("TooGenericExceptionCaught")
    fun runE2eTest(block: suspend VaultwardenFixture.() -> Unit) = runBlocking {
        val logsFrom = server.logLength()
        try {
            withTimeout(TEST_TIMEOUT) {
                block()
            }
        } catch (e: Throwable) {
            throw AssertionError("${e.message}\n${server.diagnostics(logsFrom)}", e)
        }
    }

    override fun close() {
        super.close()
        server.close()
    }

    companion object {
        private val TEST_TIMEOUT = 3.minutes

        /**
         * The server with the `server/bw/vaultwarden.env` settings, shared by every test in
         * the JVM; each test registers its own accounts. Testcontainers removes it on exit.
         * A failed start is kept, so the other tests fail at once with its cause.
         */
        val shared: VaultwardenFixture get() = sharedStart.getOrThrow()

        private val sharedStart by lazy { runCatching { start() } }

        /** Starts a server with [overrides] applied over `server/bw/vaultwarden.env`. */
        fun start(overrides: Map<String, String> = emptyMap()): VaultwardenFixture {
            val server = VaultwardenContainer.start(overrides)
            return VaultwardenFixture(server)
        }
    }
}

/** Runs [block] against [VaultwardenFixture.shared]. */
internal fun runE2eTest(block: suspend VaultwardenFixture.() -> Unit) = VaultwardenFixture.shared.runE2eTest(block)
