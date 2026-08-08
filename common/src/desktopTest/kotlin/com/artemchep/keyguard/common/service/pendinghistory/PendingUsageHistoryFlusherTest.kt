package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AddGpgUsageHistoryRequest
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.settings.impl.SettingsRepositoryImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.AddGpgUsageHistory
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PendingUsageHistoryFlusherTest {
    private val json = Json

    private val queue = FakeQueue()
    private val addGpg = RecordingAddGpgUsageHistory()
    private val addSsh = RecordingAddSshUsageHistory()
    private val settingsRepository = SettingsRepositoryImpl(
        store = JsonKeyValueStore(),
        json = json,
        base64Service = Base64ServiceImpl(),
    )

    private val flusher = PendingUsageHistoryFlusher(
        queue = queue,
        addGpgUsageHistory = addGpg,
        addSshUsageHistory = addSsh,
        vaultSettingsStore = TestVaultSettingsStore(JsonKeyValueStore()),
        settingsRepository = settingsRepository,
        base64Service = Base64ServiceImpl(),
        json = json,
        logRepository = NoopLogRepository,
    )

    @Test
    fun `flush provisions a stable envelope key pair`() = runTest {
        assertNull(settingsRepository.getExposedContentPublicKey().first())

        flusher.flush().bind()
        val publicKey = settingsRepository.getExposedContentPublicKey().first()
        assertNotNull(publicKey)

        flusher.flush().bind()
        val publicKeyAfter = settingsRepository.getExposedContentPublicKey().first()
        assertNotNull(publicKeyAfter)
        assertContentEquals(publicKey, publicKeyAfter)
    }

    @Test
    fun `flush drains sealed events into the matching usage history`() = runTest {
        flusher.flush().bind()
        val publicKey = settingsRepository.getExposedContentPublicKey().first()!!
        queue.rows += sealedRow(
            publicKey = publicKey,
            id = "event-gpg",
            timestamp = 1_000L,
            payload = PendingUsageHistoryPayload(
                protocol = PendingUsageHistory.Protocol.OPENPGP.name,
                sessionId = "test-session",
                caller = "com.example.app",
                requestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name,
                responseType = GpgUsageHistoryResponseType.SUCCESS.name,
                cipherId = null,
                fingerprint = "fingerprint",
                keygrip = "keygrip",
            ),
        )
        queue.rows += sealedRow(
            publicKey = publicKey,
            id = "event-ssh",
            timestamp = 2_000L,
            payload = PendingUsageHistoryPayload(
                protocol = PendingUsageHistory.Protocol.SSH.name,
                sessionId = "test-session",
                caller = "com.example.app",
                requestType = SshUsageHistoryRequestType.AGENT_SIGN_DATA.name,
                responseType = SshUsageHistoryResponseType.SUCCESS.name,
                cipherId = null,
                fingerprint = "fingerprint",
            ),
        )

        val result = flusher.flush().bind()

        val gpg = addGpg.requests.single()
        assertEquals("event-gpg", gpg.eventId)
        assertEquals("test-session", gpg.sessionId)
        assertEquals("com.example.app", gpg.caller)
        assertEquals(GpgUsageHistoryRequestType.AGENT_SIGN_HASH, gpg.request)
        assertEquals(GpgUsageHistoryResponseType.SUCCESS, gpg.response)
        assertEquals(Instant.fromEpochMilliseconds(1_000L), gpg.instant)
        val ssh = addSsh.requests.single()
        assertEquals("event-ssh", ssh.eventId)
        assertEquals(SshUsageHistoryRequestType.AGENT_SIGN_DATA, ssh.request)
        assertEquals(Instant.fromEpochMilliseconds(2_000L), ssh.instant)
        assertTrue(queue.rows.isEmpty())
        assertTrue(result.isComplete)
    }

    @Test
    fun `unknown request and response names map to unknown`() = runTest {
        flusher.flush().bind()
        val publicKey = settingsRepository.getExposedContentPublicKey().first()!!
        queue.rows += sealedRow(
            publicKey = publicKey,
            id = "event-unknown",
            timestamp = 1_000L,
            payload = PendingUsageHistoryPayload(
                protocol = PendingUsageHistory.Protocol.OPENPGP.name,
                sessionId = "test-session",
                requestType = "NO_SUCH_REQUEST",
                responseType = "NO_SUCH_RESPONSE",
            ),
        )

        flusher.flush().bind()

        val gpg = addGpg.requests.single()
        assertEquals(GpgUsageHistoryRequestType.UNKNOWN, gpg.request)
        assertEquals(GpgUsageHistoryResponseType.UNKNOWN, gpg.response)
    }

    @Test
    fun `unreadable rows are dropped without inserting`() = runTest {
        flusher.flush().bind()
        queue.rows += SealedPendingUsageHistory(
            id = "event-garbage",
            timestampEpochMilliseconds = 1_000L,
            payload = ByteArray(64) { it.toByte() },
        )

        flusher.flush().bind()

        assertTrue(addGpg.requests.isEmpty())
        assertTrue(addSsh.requests.isEmpty())
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun `insert retries without the cipher link before giving up`() = runTest {
        flusher.flush().bind()
        val publicKey = settingsRepository.getExposedContentPublicKey().first()!!
        addGpg.failWhen = { request -> request.cipherId != null }
        queue.rows += sealedRow(
            publicKey = publicKey,
            id = "event-dead-cipher",
            timestamp = 1_000L,
            payload = PendingUsageHistoryPayload(
                protocol = PendingUsageHistory.Protocol.OPENPGP.name,
                sessionId = "test-session",
                requestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name,
                responseType = GpgUsageHistoryResponseType.SUCCESS.name,
                cipherId = "deleted-cipher",
            ),
        )

        flusher.flush().bind()

        val gpg = addGpg.requests.single()
        assertNull(gpg.cipherId)
        assertEquals("event-dead-cipher", gpg.eventId)
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun `rows that fail to insert stay queued`() = runTest {
        flusher.flush().bind()
        val publicKey = settingsRepository.getExposedContentPublicKey().first()!!
        addGpg.failWhen = { true }
        queue.rows += sealedRow(
            publicKey = publicKey,
            id = "event-failing",
            timestamp = 1_000L,
            payload = PendingUsageHistoryPayload(
                protocol = PendingUsageHistory.Protocol.OPENPGP.name,
                sessionId = "test-session",
                requestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name,
                responseType = GpgUsageHistoryResponseType.SUCCESS.name,
            ),
        )

        val result = flusher.flush().bind()

        assertTrue(addGpg.requests.isEmpty())
        assertEquals(1, queue.rows.size)
        assertEquals(1, result.deferredRows)
    }

    private fun sealedRow(
        publicKey: ByteArray,
        id: String,
        timestamp: Long,
        payload: PendingUsageHistoryPayload,
    ): SealedPendingUsageHistory {
        val plaintext = json.encodeToString(payload).encodeToByteArray()
        return SealedPendingUsageHistory(
            id = id,
            timestampEpochMilliseconds = timestamp,
            payload = PendingUsageHistoryEnvelope.seal(
                publicKeySpki = publicKey,
                plaintext = plaintext,
            ),
        )
    }
}

private class FakeQueue : PendingUsageHistoryQueue {
    val rows = mutableListOf<SealedPendingUsageHistory>()

    override fun get(): IO<List<SealedPendingUsageHistory>> = io(rows.toList())

    override fun enqueue(item: PendingUsageHistory): IO<Unit> = ioEffect {
        error("Not used by the flusher")
    }

    override fun remove(id: String): IO<Unit> = ioEffect {
        rows.removeAll { it.id == id }
        Unit
    }
}

private class RecordingAddGpgUsageHistory : AddGpgUsageHistory {
    val requests = mutableListOf<AddGpgUsageHistoryRequest>()
    var failWhen: (AddGpgUsageHistoryRequest) -> Boolean = { false }

    override fun invoke(request: AddGpgUsageHistoryRequest): IO<Unit> = ioEffect {
        check(!failWhen(request)) { "Simulated insert failure" }
        requests += request
        Unit
    }
}

private class RecordingAddSshUsageHistory : AddSshUsageHistory {
    val requests = mutableListOf<AddSshUsageHistoryRequest>()

    override fun invoke(request: AddSshUsageHistoryRequest): IO<Unit> = ioEffect {
        requests += request
        Unit
    }
}

private class TestVaultSettingsStore(
    store: KeyValueStore,
) : VaultSettingsKeyValueStore, KeyValueStore by store

private object NoopLogRepository : LogRepository {
    override fun post(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        // Intentionally empty.
    }

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        // Intentionally empty.
    }
}
