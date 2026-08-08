package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.pendinghistory.SealedPendingUsageHistory
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidIpcUsageRecorderTest {
    private val caller =
        AndroidIpcCaller(
            uid = 10_001,
            pid = 321,
            packageName = "com.example.client",
            appLabel = "Example\u202E Client",
            certificateDigests = listOf("digest"),
        )

    @Test
    fun `encodes Android caller as structured GPG and SSH identities`() {
        val gpg =
            Json.decodeFromString<GpgAgentMessages.CallerIdentity>(
                assertNotNull(caller.encodeGpgUsageHistoryCaller(Json)),
            )
        val ssh =
            Json.decodeFromString<SshAgentMessages.CallerIdentity>(
                assertNotNull(caller.encodeSshUsageHistoryCaller(Json)),
            )

        listOf(gpg, ssh).forEach { identity ->
            assertEquals(321, identity.pid)
            assertEquals("Example\\u202e Client", identity.appName)
            assertEquals("com.example.client", identity.appBundlePath)
        }
        assertEquals(10_001, gpg.uid)
        assertEquals(10_001, ssh.uid)
    }

    @Test
    fun `recorder owns protocol-specific caller encoding`() =
        runTest {
            val queue = RecordingQueue()
            var directEvent: PendingUsageHistory? = null

            recordAndroidIpcUsage(
                directRecorder = DirectRecorder,
                historyQueue = queue,
                protocol = PendingUsageHistory.Protocol.OPENPGP,
                caller = caller,
                json = Json,
                requestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name,
                responseType = GpgUsageHistoryResponseType.SUCCESS.name,
                cipherId = "cipher",
                fingerprint = "fingerprint",
                keygrip = "keygrip",
            ) { _, event ->
                directEvent = event
            }
            recordAndroidIpcUsage<Any>(
                directRecorder = null,
                historyQueue = queue,
                protocol = PendingUsageHistory.Protocol.SSH,
                caller = caller,
                json = Json,
                requestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name,
                responseType = GpgUsageHistoryResponseType.SUCCESS.name,
                cipherId = "cipher",
                fingerprint = "fingerprint",
                keygrip = "keygrip",
            ) { _, _ ->
                error("The direct recorder must not run while the vault is locked.")
            }

            val gpgCaller =
                Json.decodeFromString<GpgAgentMessages.CallerIdentity>(
                    assertNotNull(directEvent?.caller),
                )
            val sshCaller =
                Json.decodeFromString<SshAgentMessages.CallerIdentity>(
                    assertNotNull(queue.items.single().caller),
                )
            assertEquals("com.example.client", gpgCaller.appBundlePath)
            assertEquals("com.example.client", sshCaller.appBundlePath)
        }

    @Test
    fun `failed direct write enqueues the same idempotent event`() =
        runTest {
            val queue = RecordingQueue()
            var attemptedEvent: PendingUsageHistory? = null

            recordAndroidIpcUsage(
                directRecorder = DirectRecorder,
                historyQueue = queue,
                protocol = PendingUsageHistory.Protocol.OPENPGP,
                caller = caller,
                json = Json,
                requestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name,
                responseType = GpgUsageHistoryResponseType.SUCCESS.name,
                cipherId = "cipher",
                fingerprint = "fingerprint",
                keygrip = "keygrip",
            ) { _, event ->
                attemptedEvent = event
                throw IllegalStateException("Vault session closed during the write.")
            }

            assertEquals(assertNotNull(attemptedEvent), queue.items.single())
        }

    @Test
    fun `successful direct write does not enqueue the event`() =
        runTest {
            val queue = RecordingQueue()

            recordAndroidIpcUsage(
                directRecorder = DirectRecorder,
                historyQueue = queue,
                protocol = PendingUsageHistory.Protocol.OPENPGP,
                caller = caller,
                json = Json,
                requestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name,
                responseType = GpgUsageHistoryResponseType.SUCCESS.name,
                cipherId = "cipher",
                fingerprint = "fingerprint",
                keygrip = "keygrip",
            ) { _, _ -> }

            assertTrue(queue.items.isEmpty())
        }

    @Test
    fun `direct write cancellation is not swallowed`() =
        runTest {
            assertFailsWith<CancellationException> {
                recordAndroidIpcUsage(
                    directRecorder = DirectRecorder,
                    historyQueue = RecordingQueue(),
                    protocol = PendingUsageHistory.Protocol.OPENPGP,
                    caller = caller,
                    json = Json,
                    requestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name,
                    responseType = GpgUsageHistoryResponseType.SUCCESS.name,
                    cipherId = null,
                    fingerprint = null,
                    keygrip = null,
                ) { _, _ ->
                    throw CancellationException("cancelled")
                }
            }
        }

    @Test
    fun `queue fatal failure is not swallowed`() =
        runTest {
            assertFailsWith<OutOfMemoryError> {
                recordAndroidIpcUsage<Any>(
                    directRecorder = null,
                    historyQueue = RecordingQueue(enqueueFailure = OutOfMemoryError("heap")),
                    protocol = PendingUsageHistory.Protocol.OPENPGP,
                    caller = caller,
                    json = Json,
                    requestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name,
                    responseType = GpgUsageHistoryResponseType.SUCCESS.name,
                    cipherId = null,
                    fingerprint = null,
                    keygrip = null,
                ) { _, _ -> }
            }
        }

    private data object DirectRecorder

    private class RecordingQueue(
        private val enqueueFailure: Throwable? = null,
    ) : PendingUsageHistoryQueue {
        val items = mutableListOf<PendingUsageHistory>()

        override fun get(): IO<List<SealedPendingUsageHistory>> = io(emptyList())

        override fun enqueue(item: PendingUsageHistory): IO<Unit> =
            ioEffect {
                enqueueFailure?.let { throw it }
                items += item
            }

        override fun remove(id: String): IO<Unit> = io(Unit)
    }
}
