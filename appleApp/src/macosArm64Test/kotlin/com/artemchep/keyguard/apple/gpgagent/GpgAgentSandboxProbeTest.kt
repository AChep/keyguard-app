package com.artemchep.keyguard.apple.gpgagent

import platform.posix.STDIN_FILENO
import platform.posix.arc4random_buf
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.crypto.NativeGpgAgentCrypto
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fflush
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.read
import platform.posix.socket
import platform.posix.stdout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in provisioned sandbox gate, driven by xcode/scripts/test-macos-gpg-sandbox.py.
 * Runs the production native runtime and crypto with disposable in-memory keys;
 * it never bootstraps the application's DI, preferences, or vault. */
@OptIn(ExperimentalForeignApi::class)
class GpgAgentSandboxProbeTest {
    @Test
    fun externalGnuPgThroughProvisionedSandbox() {
        if (getenv("KEYGUARD_GPG_SANDBOX_PROBE")?.toKString() != "1") return
        val group = requireNotNull(appleAppGroupContainerPath()) { "Provisioned App Group is required" }
        val root = "$group/gp${NSUUID().UUIDString.take(6)}"
        assertTrue(mkdir(root, 0x1c0u) == 0)
        try {
            runBlocking {
                val keys = listOf(
                    GpgKeyConfig.Modern("Sandbox modern <modern@keyguard.test.invalid>"),
                    GpgKeyConfig.Rsa("Sandbox RSA3072 <rsa3072@keyguard.test.invalid>", GpgKeyConfig.RsaLength.B3072),
                    GpgKeyConfig.Rsa("Sandbox RSA4096 <rsa4096@keyguard.test.invalid>", GpgKeyConfig.RsaLength.B4096),
                ).map(NativeGpgKeyGenerator::generate)
                val components = keys.flatMap { key -> requireNotNull(
                    key.metadata,
                ).routableAgentKeys.map { key to it } }
                val processor = object : GpgAgentRequestProcessor {
                    override suspend fun listKeys(caller: GpgAgentMessages.CallerIdentity?) =
                        GpgAgentRequestProcessor.ListKeysResult.Success(GpgAgentMessages.ListKeysResponse(
                            keys = components.map { (key, component) ->
                                GpgAgentMessages.GpgKey(key.userId, component.keygrip, component.fingerprint,
                                    component.algorithm, component.canSign, component.canDecrypt)
                            },
                        ))

                    override suspend fun signHash(
                        request: GpgAgentMessages.SignHashRequest,
                    ): GpgAgentRequestProcessor.GpgAgentOperationResult<
                        GpgAgentMessages.SignHashResponse,
                    > {
                        val (key, component) = components.firstOrNull { it.second.keygrip.equals(
                            request.keygrip,
                            true,
                        ) }
                            ?: return GpgAgentRequestProcessor.GpgAgentOperationResult.KeyNotFound
                        return GpgAgentRequestProcessor.GpgAgentOperationResult.Success(NativeGpgAgentCrypto.signHash(
                            key.privateKeyArmored, component, request.hashAlgorithm, request.hash,
                            keys.map { GpgOpenPgpPublicKey(it.publicKeyArmored) },
                        ))
                    }

                    override suspend fun decrypt(
                        request: GpgAgentMessages.PkdecryptRequest,
                    ): GpgAgentRequestProcessor.GpgAgentOperationResult<
                        GpgAgentMessages.PkdecryptResponse,
                    > {
                        val (key, component) = components.firstOrNull { it.second.keygrip.equals(
                            request.keygrip,
                            true,
                        ) }
                            ?: return GpgAgentRequestProcessor.GpgAgentOperationResult.KeyNotFound
                        return GpgAgentRequestProcessor.GpgAgentOperationResult.Success(NativeGpgAgentCrypto.pkdecrypt(
                            key.privateKeyArmored, component, request.ciphertext, request.unwrapEcdh,
                        ))
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val token = ByteArray(
                    32,
                ).also { bytes -> bytes.usePinned { arc4random_buf(
                    it.addressOf(0),
                    bytes.size.convert(),
                ) } }
                try {
                    val runtime = MacosGpgAgentRuntime(groupPath = { root })
                    val handle = runtime.start(scope, GpgAgentRuntimeConfig(token, processor, {}, {}))
                    val manifest = buildJsonObject {
                        put("socket", requireNotNull(handle.agentSocket))
                        put(
                            "setup",
                            com.artemchep.keyguard.feature.gpgagent.help.macosSandboxGpgAgentSetupCommand(
                                requireNotNull(handle.agentSocket),
                            ),
                        )
                        putJsonArray("keys") {
                            keys.forEach { key -> add(buildJsonObject {
                                put("fingerprint", key.fingerprint)
                                put("publicKey", key.publicKeyArmored)
                            }) }
                        }
                    }
                    println("KEYGUARD_GPG_PROBE $manifest")
                    fflush(stdout)
                    // The external driver must not need file access to another
                    // application's container (restricted on macOS 27).
                    val flags = fcntl(STDIN_FILENO, F_GETFL)
                    check(flags >= 0 && fcntl(STDIN_FILENO, F_SETFL, flags or O_NONBLOCK) == 0)
                    val signal = ByteArray(1)
                    withTimeout(120_000) {
                        while (true) {
                            val count = signal.usePinned { read(STDIN_FILENO, it.addressOf(0), 1u) }
                            if (count == 1L) break
                            check(count < 0L && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                                "Sandbox probe driver disconnected"
                            }
                            check(handle.isRunning) { "Sandbox helper died" }
                            delay(50)
                        }
                    }
                    handle.stop()
                } finally {
                    token.fill(0)
                    scope.coroutineContext.job.cancelAndJoin()
                }
            }
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }
}
