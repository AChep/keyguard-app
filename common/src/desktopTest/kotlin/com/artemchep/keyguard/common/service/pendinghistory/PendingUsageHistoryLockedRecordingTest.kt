package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor.GpgAgentOperationResult
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestProcessorImpl
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessorImpl
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.crypto.NativeGpgAgentCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The agent request processors cannot reach the usage-history tables
 * while the vault is locked; these tests pin that locked-state events
 * are captured into the pending usage-history queue instead of being
 * silently dropped.
 */
class PendingUsageHistoryLockedRecordingTest {
    companion object {
        private const val SESSION_ID = "test-agent-session"
        private const val KEYGRIP = "0123456789ABCDEF0123456789ABCDEF01234567"
    }

    private val queue = RecordingQueue()

    @Test
    fun `locked GPG list-keys is recorded into the pending queue`() = runTest {
        val processor = createGpgProcessor(approve = { true })

        processor.listKeys(caller = null)

        val event = queue.items.single()
        assertEquals(PendingUsageHistory.Protocol.OPENPGP, event.protocol)
        assertEquals(SESSION_ID, event.sessionId)
        assertEquals(GpgUsageHistoryRequestType.AGENT_LIST_KEYS.name, event.requestType)
        assertEquals(GpgUsageHistoryResponseType.SUCCESS.name, event.responseType)
        // Routine probes must coalesce so they can not evict rarer
        // events past the queue cap.
        assertNotNull(event.coalescenceKey)
    }

    @Test
    fun `locked GPG signing denial is recorded into the pending queue`() = runTest {
        val processor = createGpgProcessor(approve = { false })

        val result = processor.signHash(
            GpgAgentMessages.SignHashRequest(keygrip = KEYGRIP),
        )

        assertIs<GpgAgentOperationResult.UserDenied>(result)
        val event = queue.items.single()
        assertEquals(GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name, event.requestType)
        assertEquals(GpgUsageHistoryResponseType.USER_DENIED.name, event.responseType)
        assertEquals(KEYGRIP, event.keygrip)
        // Denials are the events the queue exists for; every one of
        // them must survive as its own row.
        assertNull(event.coalescenceKey)
    }

    @Test
    fun `GPG approval that does not unlock the vault is recorded`() = runTest {
        val processor = createGpgProcessor(approve = { true })

        val result = processor.signHash(
            GpgAgentMessages.SignHashRequest(keygrip = KEYGRIP),
        )

        assertIs<GpgAgentOperationResult.VaultLocked>(result)
        val event = queue.items.single()
        assertEquals(GpgUsageHistoryRequestType.AGENT_SIGN_HASH.name, event.requestType)
        assertEquals(GpgUsageHistoryResponseType.VAULT_LOCKED.name, event.responseType)
    }

    @Test
    fun `locked SSH list-keys is recorded into the pending queue`() = runTest {
        val processor = createSshProcessor(approve = { true })

        processor.listKeys(caller = null)

        val event = queue.items.single()
        assertEquals(PendingUsageHistory.Protocol.SSH, event.protocol)
        assertEquals(SESSION_ID, event.sessionId)
        assertEquals(SshUsageHistoryRequestType.AGENT_LIST_KEYS.name, event.requestType)
        assertEquals(SshUsageHistoryResponseType.SUCCESS.name, event.responseType)
        // Routine probes must coalesce so they can not evict rarer
        // events past the queue cap.
        assertNotNull(event.coalescenceKey)
    }

    @Test
    fun `locked SSH signing denial is recorded into the pending queue`() = runTest {
        val processor = createSshProcessor(approve = { false })

        val result = processor.signData(
            SshAgentMessages.SignDataRequest(
                publicKey = "ssh-ed25519 AAAA test",
            ),
        )

        assertIs<SshAgentRequestProcessor.SignDataResult.UserDenied>(result)
        val event = queue.items.single()
        assertEquals(SshUsageHistoryRequestType.AGENT_SIGN_DATA.name, event.requestType)
        assertEquals(SshUsageHistoryResponseType.USER_DENIED.name, event.responseType)
        // Denials are the events the queue exists for; every one of
        // them must survive as its own row.
        assertNull(event.coalescenceKey)
    }

    @Test
    fun `SSH approval that does not unlock the vault is recorded`() = runTest {
        val processor = createSshProcessor(approve = { true })

        val result = processor.signData(
            SshAgentMessages.SignDataRequest(
                publicKey = "ssh-ed25519 AAAA test",
            ),
        )

        assertIs<SshAgentRequestProcessor.SignDataResult.VaultLocked>(result)
        val event = queue.items.single()
        assertEquals(SshUsageHistoryRequestType.AGENT_SIGN_DATA.name, event.requestType)
        assertEquals(SshUsageHistoryResponseType.VAULT_LOCKED.name, event.responseType)
    }

    private fun TestScope.createGpgProcessor(
        approve: suspend () -> Boolean,
    ) = GpgAgentRequestProcessorImpl(
        logRepository = NoOpLogRepository,
        crypto = NativeGpgAgentCrypto,
        getVaultSession = LockedGetVaultSession,
        getGpgAgentApprovalWindow = GetGpgAgentApprovalWindowNoOp,
        getGpgAgentFilter = object : GetGpgAgentFilter {
            override fun invoke(): Flow<GpgAgentFilter> = flowOf(GpgAgentFilter())
        },
        scope = backgroundScope,
        pendingUsageHistoryQueue = queue,
        sessionId = SESSION_ID,
        onApprovalRequest = { approve() },
    )

    private fun TestScope.createSshProcessor(
        approve: suspend () -> Boolean,
    ) = SshAgentRequestProcessorImpl(
        logRepository = NoOpLogRepository,
        getVaultSession = LockedGetVaultSession,
        getSshAgentApprovalWindow = GetSshAgentApprovalWindowNoOp,
        getSshAgentFilter = object : GetSshAgentFilter {
            override fun invoke(): Flow<SshAgentFilter> = flowOf(SshAgentFilter())
        },
        scope = backgroundScope,
        pendingUsageHistoryQueue = queue,
        sessionId = SESSION_ID,
        onApprovalRequest = { approve() },
    )

    private class RecordingQueue : PendingUsageHistoryQueue {
        val items = mutableListOf<PendingUsageHistory>()

        override fun get(): IO<List<SealedPendingUsageHistory>> = io(emptyList())

        override fun enqueue(item: PendingUsageHistory): IO<Unit> {
            items += item
            return ioUnit()
        }

        override fun remove(id: String): IO<Unit> = ioUnit()
    }

    private object LockedGetVaultSession : GetVaultSession {
        override val valueOrNull: MasterSession? = null

        override fun invoke(): Flow<MasterSession> = emptyFlow()
    }

    private object NoOpLogRepository : LogRepository {
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
}
