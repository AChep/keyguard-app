package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.common.service.agent.AgentIpcFraming
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.chmod
import platform.posix.memcpy
import platform.posix.mkdir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)
class SshAgentRuntimeMacosTest {
    @Test
    fun readinessStopAndRestartKeepTheSshAuthSockPath() = inTemporaryDirectory { root ->
        runBlocking {
            val binary = "$root/helper"
            // Emulates the lifecycle contract, without cryptography or a vault.
            writeHelper(
                binary,
                "IFS= read -r token\nprintf '%s\\n' \"\$@\" > \"$root/args\"\n" +
                    "printf 'KEYGUARD_AGENT_READY 1\\n'\nwhile IFS= read -r line; do :; done\n",
            )
            val runtime = MacosSshAgentRuntime({ binary }, { root })
            assertEquals("$root/ssh-agent.sock", runtime.sshAuthSockPath)
            repeat(2) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val handle = assertNotNull(withTimeout(5_000) { runtime.start(scope, config()) })
                    assertTrue(handle.isRunning)
                    assertEquals("$root/ssh-agent.sock", handle.sshAuthSockPath)
                    val args = readText("$root/args").lines().filter(String::isNotEmpty)
                    assertEquals("--ipc-socket", args[0])
                    assertTrue(args[1].startsWith("$root/ssh/"))
                    assertEquals(listOf("--ssh-socket", "$root/ssh-agent.sock"), args.drop(4))
                    handle.stop()
                } finally {
                    withTimeout(5_000) { scope.coroutineContext.job.cancelAndJoin() }
                }
                val leftovers = NSFileManager.defaultManager.contentsOfDirectoryAtPath("$root/ssh", null)
                assertEquals(emptyList<Any?>(), leftovers)
            }
        }
    }

    @Test
    fun aWrongTokenIsRejectedOverThePrivateSocket() = inTemporaryDirectory { root ->
        runBlocking {
            val request = SshAgentMessages.IpcRequest(
                id = 1L,
                authenticate = AgentIpcMessages.AuthenticateRequest(
                    token = ByteArray(32) { 9 },
                    protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
                ),
            )
            writeBytes("$root/request", frame(ProtoBuf.encodeToByteArray(request)))
            val binary = "$root/helper"
            // The server accepts only its child process, so the helper itself
            // becomes the client, keeping its process identity.
            writeHelper(
                binary,
                "IFS= read -r token\nprintf 'KEYGUARD_AGENT_READY 1\\n'\n" +
                    "exec /usr/bin/nc -U \"\$2\" < \"$root/request\" > \"$root/response\"\n",
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val expected = frame(ProtoBuf.encodeToByteArray(rejection()))
            try {
                // The helper can finish before its readiness is confirmed; the
                // reply it received is what matters.
                withTimeout(5_000) { MacosSshAgentRuntime({ binary }, { root }).start(scope, config()) }
                withTimeout(5_000) {
                    while (readBytes("$root/response").size < expected.size) delay(20)
                }
            } finally {
                withTimeout(5_000) { scope.coroutineContext.job.cancelAndJoin() }
            }
            assertEquals(expected.toHexString(), readBytes("$root/response").toHexString())
        }
    }

    private fun rejection() = SshAgentMessages.IpcResponse(
        id = 1L,
        authenticate = AgentIpcMessages.AuthenticateResponse(
            success = false,
            protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
        ),
    )

    private fun frame(payload: ByteArray) =
        AgentIpcFraming.encodeHeader(payload.size, AgentIpcFraming.MAX_PACKET_SIZE) + payload

    private fun config() = SshAgentRuntimeConfig(
        authToken = ByteArray(32) { it.toByte() },
        processor = object : SshAgentRequestProcessor {
            override suspend fun listKeys(
                caller: CallerIdentity?,
            ) = SshAgentRequestProcessor.ListKeysResult.VaultLocked

            override suspend fun signData(
                request: SshAgentMessages.SignDataRequest,
            ) = SshAgentRequestProcessor.SignDataResult.VaultLocked
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

    private fun writeHelper(path: String, script: String) {
        assertTrue(NSString.create(string = "#!/bin/sh\n$script").writeToFile(path, true, NSUTF8StringEncoding, null))
        assertEquals(0, chmod(path, 0x1c0u))
    }

    private fun readText(path: String): String =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null).orEmpty()

    private fun writeBytes(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
        assertTrue(data.writeToFile(path, true))
    }

    private fun readBytes(path: String): ByteArray {
        val data = NSFileManager.defaultManager.contentsAtPath(path) ?: return ByteArray(0)
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }
}
