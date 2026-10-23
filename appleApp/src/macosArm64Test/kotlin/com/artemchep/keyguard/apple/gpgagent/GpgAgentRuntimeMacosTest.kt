package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.apple.agent.ensurePrivateDirectory
import com.artemchep.keyguard.apple.agent.requireSocketPath
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.chmod
import platform.posix.mkdir
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class GpgAgentRuntimeMacosTest {
    @Test
    fun socketPathsUseEncodedDarwinLimit() {
        requireSocketPath("/" + "a".repeat(102))
        assertFailsWith<IllegalArgumentException> { requireSocketPath("/" + "a".repeat(103)) }
        assertFailsWith<IllegalArgumentException> { requireSocketPath("/" + "é".repeat(52)) }
        assertFailsWith<IllegalArgumentException> { requireSocketPath("relative/socket") }
        assertFailsWith<IllegalArgumentException> { requireSocketPath("/path\u0000/socket") }
    }

    @Test
    fun privateDirectoryRefusesSymlinksFilesAndUnsafePermissions() = inTemporaryDirectory { root ->
        ensurePrivateDirectory("$root/valid")
        ensurePrivateDirectory("$root/valid")
        assertEquals(0, symlink("$root/valid", "$root/link"))
        assertFailsWith<IllegalStateException> { ensurePrivateDirectory("$root/link") }
        writeText("$root/file", "keep")
        assertFailsWith<IllegalStateException> { ensurePrivateDirectory("$root/file") }
        assertEquals(0, mkdir("$root/shared", 0x1c0u))
        assertEquals(0, chmod("$root/shared", 0x1ffu))
        assertFailsWith<IllegalStateException> { ensurePrivateDirectory("$root/shared") }
    }

    @Test
    fun launchFailureAndCancellationRemoveOnlyPrivateIpc() = inTemporaryDirectory { root ->
        runBlocking {
            ensurePrivateDirectory("$root/gpg")
            writeText("$root/gpg/s", "unrelated socket-path occupant")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                assertFailsWith<IllegalStateException> {
                    MacosGpgAgentRuntime({ "/usr/bin/false" }, { root }).start(scope, config())
                }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
            val children = NSFileManager.defaultManager.contentsOfDirectoryAtPath("$root/gpg", null)
            assertEquals(setOf("s", "locks"), children?.toSet())
        }
    }

    @Test
    fun readinessStopAndRestartHaveCompleteCleanup() = inTemporaryDirectory { root ->
        runBlocking {
            val binary = "$root/helper"
            // Emulates the lifecycle contract, without cryptography or a vault.
            writeText(
                binary,
                "#!/bin/sh\nIFS= read -r token\n" +
                    "printf 'KEYGUARD_AGENT_READY 1\\n'\nwhile IFS= read -r line; do :; done\n",
            )
            assertEquals(0, chmod(binary, 0x1c0u))
            val runtime = MacosGpgAgentRuntime({ binary }, { root })
            repeat(2) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val handle = withTimeout(5_000) { runtime.start(scope, config()) }
                    assertTrue(handle.isRunning)
                    assertEquals("$root/gpg/s", handle.agentSocket)
                    assertEquals("~/.keyguard/gnupg", handle.gpgHome)
                    handle.stop()
                } finally {
                    withTimeout(5_000) { scope.coroutineContext.job.cancelAndJoin() }
                }
                assertEquals(listOf("locks"), NSFileManager.defaultManager.contentsOfDirectoryAtPath("$root/gpg", null))
            }
        }
    }

    private fun config() = GpgAgentRuntimeConfig(
        authToken = ByteArray(32) { it.toByte() },
        processor = object : GpgAgentRequestProcessor {
            override suspend fun listKeys(
                caller: CallerIdentity?,
            ) = GpgAgentRequestProcessor.ListKeysResult.VaultLocked
            override suspend fun signHash(
                request: GpgAgentMessages.SignHashRequest,
            ) = GpgAgentRequestProcessor.GpgAgentOperationResult.VaultLocked
            override suspend fun decrypt(
                request: GpgAgentMessages.PkdecryptRequest,
            ) = GpgAgentRequestProcessor.GpgAgentOperationResult.VaultLocked
        },
        onTerminated = {},
        log = {},
    )

    private fun inTemporaryDirectory(block: (String) -> Unit) {
        val path = "/private/tmp/kg-${NSUUID().UUIDString.take(8)}"
        assertEquals(0, mkdir(path, 0x1c0u))
        try { block(path) } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    private fun writeText(path: String, text: String) {
        assertTrue(NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null))
    }
}
