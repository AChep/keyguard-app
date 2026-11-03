package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.test.TestCryptoGenerator
import com.artemchep.keyguard.test.withTempDirectory
import com.artemchep.keyguard.util.agentipc.AgentIpcException
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcFailureKind
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A helper outlives a failed IPC transport unless its manager stops it: it
 * would keep its public socket with no one to serve it, and its supervisor
 * would keep waiting for it to exit.
 */
class AgentManagerTransportFailureTest {
    @Test
    fun `a helper whose transport fails is stopped`() = runBlocking {
        withFakeHelper { helper ->
            val log = AgentIpcTestLog()
            val failure = CompletableDeferred<Unit>()
            val manager = TransportAgentManager(log) { expectedPeer ->
                assertNotNull(expectedPeer().currentPid(), "The helper must be published first")
                failure.await()
                throw AgentIpcException(AgentIpcFailureKind.INTERNAL, diagnostic = "accept: listener lost")
            }
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val process = manager.start(scope, binaryPath = helper)
                assertTrue(process.isAlive)

                failure.complete(Unit)
                val exited = runInterruptible(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) }
                assertTrue(exited, "The helper must exit once its transport fails")
                assertContains(log.errors, "IPC server failed: internal")
            } finally {
                manager.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun `the supervisor restarts a helper whose transport failed`() = runBlocking {
        withFakeHelper { helper ->
            val failure = CompletableDeferred<Unit>()
            val runs = AtomicInteger()
            val manager = TransportAgentManager(AgentIpcTestLog()) { expectedPeer ->
                expectedPeer()
                if (runs.incrementAndGet() > 1) awaitCancellation()
                failure.await()
                throw AgentIpcException(AgentIpcFailureKind.INTERNAL)
            }
            val started = Channel<Process>(Channel.UNLIMITED)
            val supervisor = launch(Dispatchers.IO) {
                superviseAgent(
                    enabled = flowOf(true),
                    available = true,
                    status = {},
                    policy = AgentRestartPolicy(initialDelay = 10.milliseconds),
                    start = { scope ->
                        manager.start(scope, binaryPath = helper).also { started.send(it) }
                    },
                    // As the desktop app waits for its helpers.
                    join = { process ->
                        val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
                        "Test agent process exited unexpectedly: $exitCode"
                    },
                    stop = { manager.stop() },
                )
            }
            try {
                val first = withTimeout(10_000L) { started.receive() }
                failure.complete(Unit)
                val second = withTimeout(10_000L) { started.receive() }
                assertFalse(first.isAlive, "The failed helper must have exited")
                assertNotEquals(first.pid(), second.pid())
                assertTrue(second.isAlive)
            } finally {
                supervisor.cancelAndJoin()
            }
        }
    }

    /**
     * Runs [block] with a fake helper: it reads the token, reports that it is
     * ready, then reads its input to the end, as the real helpers do.
     */
    private inline fun <T> withFakeHelper(block: (Path) -> T): T = withTempDirectory("keyguard-fake-agent") { dir ->
        val helper = if (isWindowsHost) {
            // A batch file runs in cmd.exe, which `findstr` keeps waiting for the input to end.
            dir.resolve("fake-agent.cmd").apply {
                writeText(
                    """
                    |@echo off
                    |set /p TOKEN=
                    |echo $AGENT_STARTUP_READY_RECORD
                    |findstr "^" >nul
                    |
                    """.trimMargin().replace("\n", "\r\n"),
                )
            }
        } else {
            dir.resolve("fake-agent.sh").apply {
                writeText(
                    """
                    |#!/bin/sh
                    |IFS= read -r token
                    |echo "$AGENT_STARTUP_READY_RECORD"
                    |while IFS= read -r line; do :; done
                    |
                    """.trimMargin(),
                )
                setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))
            }
        }
        block(helper)
    }

    /** An agent manager whose IPC server binds nothing and serves with [serve]. */
    private class TransportAgentManager(
        logRepository: LogRepository,
        private val serve: suspend (expectedPeer: suspend () -> AgentIpcExpectedPeer) -> Unit,
    ) : AgentManager(
        logRepository = logRepository,
        cryptoGenerator = SeedCryptoGenerator,
        config = Config(
            tag = "TestAgent",
            displayName = "Test agent",
            binaryBaseName = "keyguard-test-agent",
            ipcSocketPrefix = "keyguard-test-ipc",
            agentSocketArg = "--test-socket",
            agentSocketLogLabel = "Test socket",
            startupReadyTimeoutMs = 5_000L,
        ),
    ) {
        override fun createIpcServer(
            authToken: ByteArray,
            sessionId: String,
            scope: CoroutineScope,
        ): IpcServerRunner = object : IpcServerRunner {
            override fun bind(location: AgentIpcLocation): String = "keyguard-test-ipc"

            override suspend fun serve(expectedPeer: suspend () -> AgentIpcExpectedPeer) {
                this@TransportAgentManager.serve(expectedPeer)
            }

            override fun close() = Unit
        }
    }

    private object SeedCryptoGenerator : TestCryptoGenerator() {
        override fun seed(length: Int): ByteArray = ByteArray(length) { it.toByte() }
    }
}
